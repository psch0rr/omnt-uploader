package io.github.psch0rr.omntuploader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Testzeilen aus echten OMNT-Logs (Pixel 8, Oktober/November 2026). */
class LineProtocolTest {

    private val tags = "android_version=16,device=phone-1,manufacturer=Google,measurement_name=test," +
        "model=Pixel\\ 8,radio_version=g5300i-250909-251024-B-14326967\\,g5300i-250909-251024-B-14326967," +
        "sdk_version=36,security_patch=2026-02-05,soc_model=Tensor\\ G3"

    @Test
    fun pingLineIsValid() {
        val c = LineProtocol.check("Ping,$tags,toHost=192.0.2.10 icmp_seq=1i,rtt=254.0,ttl=57i 1794925223833")
        assertTrue(c.reason, c.ok)
        assertEquals("Ping", c.parsed!!.measurement)
        assertEquals("Pixel 8", c.parsed!!.tags["model"])
        assertEquals(1794925223833L, c.parsed!!.timestampMs)
    }

    @Test
    fun iperfLineIsValid() {
        val c = LineProtocol.check(
            "Iperf3,$tags,bandwidth=500M,bidir=true,protocol=UDP,sender=false,version=iperf\\ 3.17.1 " +
                "bits_per_second=213301953.781419,bytes=133320256i,jitter_ms=0.01717543049542493," +
                "lost_packets=84387i,lost_percent=47.822440340249,packets=176459i,seconds=5.000245094299316 1794924060000"
        )
        assertTrue(c.reason, c.ok)
    }

    @Test
    fun stringFieldWithSpacesAndDigitsIsValid() {
        // "Vodafone 5G" hätte der alte grep-Filter fälschlich verworfen
        val c = LineProtocol.check(
            "NetworkInformation,$tags NetworkOperatorName=\"Vodafone 5G Net\",PhoneType=1i 1791536422352"
        )
        assertTrue(c.reason, c.ok)
    }

    @Test
    fun interleavedLinesAreRejected() {
        val c = LineProtocol.check(
            "Location,$tags accuracy=4.32,latitude=48.1234567,longitude=7.1234567,provider=\"fused\" " +
                "1791536NetworkInformation,$tags DataState=2i 1791536422352"
        )
        assertFalse(c.ok)
    }

    @Test
    fun fragmentWithUnknownMeasurementIsRejected() {
        val c = LineProtocol.check("26-02-05,soc_model=Tensor\\ G3 ARFCN=1300i 1791536400000")
        assertFalse(c.ok)
        assertTrue(c.reason.startsWith("unknown measurement"))
    }

    @Test
    fun shortTimestampIsRejected() {
        assertFalse(LineProtocol.check("Ping,$tags icmp_seq=1i,rtt=1.0 1791536").ok)
    }

    @Test
    fun analyzeCountsAndDetectsJson() {
        val lines = sequenceOf(
            "Ping,$tags,toHost=a icmp_seq=1i,rtt=1.0,ttl=57i 1794925223833\r",
            "",
            "26-02-05,x=y a=1i 1794925223834",
            "Ping,$tags,toHost=a icmp_seq=2i,rtt=2.0,ttl=57i 1794925224833",
        )
        val a = LineProtocol.analyze(lines)
        assertEquals(3, a.total)
        assertEquals(2, a.valid)
        assertEquals(1, a.rejectedCount)
        assertEquals(3, a.rejected.first().lineNo)
        assertEquals(setOf("test"), a.runs)
        assertEquals(1794925223833L, a.firstMs)

        val json = LineProtocol.analyze(sequenceOf("{\"event\":\"start\",\"data\":{}}"))
        assertTrue(json.error != null)
    }

    @Test
    fun analyzeCountsDistinctTimestampsPerMeasurement() {
        val a = LineProtocol.analyze(sequenceOf(
            "CellInformation,$tags,cell_key=a ARFCN=1i 1794925223833",
            "CellInformation,$tags,cell_key=b ARFCN=2i 1794925223833",   // gleicher Zeitpunkt, andere Zelle
            "SignalStrength,$tags RSRP=-90i 1794925223833",
        ))
        assertEquals(mapOf("CellInformation" to 1, "SignalStrength" to 1), a.tsCounts)
    }

    @Test
    fun dbCheckQueryAndCsv() {
        val client = InfluxClient(InfluxSettings(bucket = "my-bucket"))
        val a = LineProtocol.Analysis(runs = setOf("run1"), devices = setOf("phone-1"),
            firstMs = 1794925223833L, lastMs = 1794925224833L, tsCounts = mapOf("Ping" to 2))
        val q = client.checkQuery(a)
        assertTrue(q, q.contains("range(start: 2026-11-17T14:20:23.833Z, stop: 2026-11-17T14:20:24.834Z)"))
        assertTrue(q, q.contains("(r._measurement == \"Ping\") and (r.measurement_name == \"run1\") and (r.device == \"phone-1\")"))
        val csv = "#datatype,string,long,string,long\n#group,false,false,true,false\n#default,_result,,,\n" +
            ",result,table,_measurement,_value\n,,0,Ping,900\n,,1,SignalStrength,3\n\n"
        assertEquals(mapOf("Ping" to 900, "SignalStrength" to 3), client.parseCountCsv(csv))
    }

    @Test
    fun uploadAbortsWhenDatabaseUnreachable() {
        // Port 1 auf localhost: Verbindung wird sofort abgelehnt
        val client = InfluxClient(InfluxSettings(url = "http://127.0.0.1:1", token = "t", batchSize = 1))
        val lines = (0 until 3).map { "Ping,$tags,toHost=a icmp_seq=${it}i,rtt=1.0 179492522383$it" }
        val r = client.upload(lines.asSequence()) { }
        assertTrue(r.aborted)
        assertEquals(0, r.written)
        assertTrue(r.errors.first(), r.errors.first().startsWith("Database not reachable"))
    }
}
