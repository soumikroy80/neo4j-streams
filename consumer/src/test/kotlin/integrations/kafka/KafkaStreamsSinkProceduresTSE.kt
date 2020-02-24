package integrations.kafka

import io.confluent.kafka.serializers.KafkaAvroDeserializer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.apache.avro.SchemaBuilder
import org.apache.avro.generic.GenericRecordBuilder
import org.apache.kafka.clients.producer.ProducerRecord
import org.apache.kafka.common.TopicPartition
import org.junit.Test
import org.neo4j.kernel.api.procedure.GlobalProcedures
import streams.KafkaTestUtils
import streams.extensions.execute
import streams.extensions.toMap
import streams.procedures.StreamsSinkProcedures
import streams.serialization.JSONUtils
import streams.setConfig
import streams.start
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@Suppress("UNCHECKED_CAST", "DEPRECATION")
class KafkaStreamsSinkProceduresTSE : KafkaEventSinkBaseTSE() {

    private fun testProcedure(topic: String) {
        registerProcedure()
        val producerRecord = ProducerRecord(topic, UUID.randomUUID().toString(), JSONUtils.writeValueAsBytes(data))
        kafkaProducer.send(producerRecord).get()
        db.execute("CALL streams.consume('$topic', {timeout: 5000}) YIELD event RETURN event") { result ->
            assertTrue { result.hasNext() }
            val resultMap = result.next()
            assertTrue { resultMap.containsKey("event") }
            assertNotNull(resultMap["event"], "should contain event")
            val event = resultMap["event"] as Map<String, Any?>
            val resultData = event["data"] as Map<String, Any?>
            assertEquals(data, resultData)
        }
    }

    @Test
    fun shouldConsumeDataFromProcedureWithSinkDisabled() {
        db.setConfig("streams.sink.enabled", "false")
        db.start()
        val topic = "bar"
        testProcedure(topic)
    }

    @Test
    fun shouldConsumeDataFromProcedure() {
        db.start()
        val topic = "foo"
        testProcedure(topic)
    }

    @Test
    fun shouldTimeout() {
        db.start()
        registerProcedure()
        db.execute("CALL streams.consume('foo1', {timeout: 2000}) YIELD event RETURN event") {
            assertFalse { it.hasNext() }
        }
    }

    @Test
    fun shouldReadArrayOfJson() {
        db.start()
        registerProcedure()
        val topic = "array-topic"
        val list = listOf(data, data)
        val producerRecord = ProducerRecord(topic, UUID.randomUUID().toString(), JSONUtils.writeValueAsBytes(list))
        kafkaProducer.send(producerRecord).get()
        db.execute("""
            CALL streams.consume('$topic', {timeout: 5000}) YIELD event
            UNWIND event.data AS data
            CREATE (t:TEST) SET t += data.properties
        """.trimIndent())
        db.execute("MATCH (t:TEST) WHERE properties(t) = ${'$'}props RETURN count(t) AS count", mapOf("props" to dataProperties)) { searchResult ->
            assertTrue { searchResult.hasNext() }
            val searchResultMap = searchResult.next()
            assertTrue { searchResultMap.containsKey("count") }
            assertEquals(2L, searchResultMap["count"])
        }
    }

    @Test
    fun shouldReadSimpleDataType() {
        db.start()
        registerProcedure()
        val topic = "simple-data"
        val simpleInt = 1
        val simpleBoolean = true
        val simpleString = "test"
        var producerRecord = ProducerRecord(topic, UUID.randomUUID().toString(), JSONUtils.writeValueAsBytes(simpleInt))
        kafkaProducer.send(producerRecord).get()
        producerRecord = ProducerRecord(topic, UUID.randomUUID().toString(), JSONUtils.writeValueAsBytes(simpleBoolean))
        kafkaProducer.send(producerRecord).get()
        producerRecord = ProducerRecord(topic, UUID.randomUUID().toString(), JSONUtils.writeValueAsBytes(simpleString))
        kafkaProducer.send(producerRecord).get()
        db.execute("""
            CALL streams.consume('$topic', {timeout: 5000}) YIELD event
            MERGE (t:LOG{simpleData: event.data})
            RETURN count(t) AS insert
        """.trimIndent())
        db.execute("""
            MATCH (l:LOG)
            WHERE l.simpleData IN [$simpleInt, $simpleBoolean, "$simpleString"]
            RETURN count(l) as count
        """.trimIndent()) { searchResult ->
            assertTrue { searchResult.hasNext() }
            val searchResultMap = searchResult.next()
            assertTrue { searchResultMap.containsKey("count") }
            assertEquals(3L, searchResultMap["count"])
        }
    }

    @Test
    fun shouldReadATopicPartitionStartingFromAnOffset() = runBlocking {
        db.start()
        registerProcedure()
        val topic = "read-from-range"
        val simpleInt = 1
        val partition = 0
        var start = -1L
        (1..10).forEach {
            val producerRecord = ProducerRecord(topic, partition, UUID.randomUUID().toString(), JSONUtils.writeValueAsBytes(simpleInt))
            val recordMetadata = kafkaProducer.send(producerRecord).get()
            if (it == 6) {
                start = recordMetadata.offset()
            }
        }
        delay(1000)
        db.execute("""
            CALL streams.consume('$topic', {timeout: 5000, partitions: [{partition: $partition, offset: $start}]}) YIELD event
            CREATE (t:LOG{simpleData: event.data})
            RETURN count(t) AS insert
        """.trimIndent())

        val count = db.execute("""
            MATCH (l:LOG)
            RETURN count(l) as count
        """.trimIndent()) {
            it.columnAs<Long>("count").next()
        }
        assertEquals(5L, count)
    }

    @Test
    fun shouldReadFromLatest() = runBlocking {
        db.start()
        registerProcedure()
        val topic = "simple-data-from-latest"
        val simpleInt = 1
        val simpleString = "test"
        val partition = 0
        (1..10).forEach {
            val producerRecord = ProducerRecord(topic, partition, UUID.randomUUID().toString(), JSONUtils.writeValueAsBytes(simpleInt))
            kafkaProducer.send(producerRecord).get()
        }
        delay(1000) // should ignore the three above
        GlobalScope.launch(Dispatchers.IO) {
            delay(1000)
            val producerRecord = ProducerRecord(topic, partition, UUID.randomUUID().toString(), JSONUtils.writeValueAsBytes(simpleString))
            kafkaProducer.send(producerRecord).get()
        }
        db.execute("""
            CALL streams.consume('$topic', {timeout: 5000, from: 'latest', groupId: 'foo'}) YIELD event
            CREATE (t:LOG{simpleData: event.data})
            RETURN count(t) AS insert
        """.trimIndent())
        db.execute("""
            MATCH (l:LOG)
            RETURN count(l) AS count
        """.trimIndent()) { searchResult ->
            assertTrue { searchResult.hasNext() }
            val searchResultMap = searchResult.next()
            assertTrue { searchResultMap.containsKey("count") }
            assertEquals(1L, searchResultMap["count"])
        }
    }

    @Test
    fun shouldNotCommit() {
        db.start()
        registerProcedure()
        val topic = "simple-data"
        val simpleInt = 1
        val partition = 0
        var producerRecord = ProducerRecord(topic, partition, UUID.randomUUID().toString(), JSONUtils.writeValueAsBytes(simpleInt))
        kafkaProducer.send(producerRecord).get()
        db.execute("""
            CALL streams.consume('$topic', {timeout: 5000, autoCommit: false, commit:false}) YIELD event
            MERGE (t:LOG{simpleData: event.data})
            RETURN count(t) AS insert
        """.trimIndent())
        db.execute("""
            MATCH (l:LOG)
            RETURN count(l) as count
        """.trimIndent()) { searchResult ->
            assertTrue { searchResult.hasNext() }
            val searchResultMap = searchResult.next()
            assertTrue { searchResultMap.containsKey("count") }
            assertEquals(1L, searchResultMap["count"])
        }
        val kafkaConsumer = KafkaTestUtils.createConsumer<String, ByteArray>(
                bootstrapServers = KafkaEventSinkSuiteIT.kafka.bootstrapServers,
                schemaRegistryUrl = KafkaEventSinkSuiteIT.schemaRegistry.getSchemaRegistryUrl())
        val offsetAndMetadata = kafkaConsumer.committed(TopicPartition(topic, partition))
        assertNull(offsetAndMetadata)
    }

    @Test
    fun `should consume AVRO messages`() {
        db.start()
        registerProcedure()
        val PLACE_SCHEMA = SchemaBuilder.builder("com.namespace")
                .record("Place").fields()
                .name("name").type().stringType().noDefault()
                .name("coordinates").type().array().items().doubleType().noDefault()
                .name("citizens").type().longType().noDefault()
                .endRecord()
        val coordinates = listOf(42.30000, -11.22222)
        val citizens = 1_000_000L
        val struct = GenericRecordBuilder(PLACE_SCHEMA)
                .set("name", "Foo")
                .set("coordinates", coordinates)
                .set("citizens", citizens)
                .build()
        val topic = "avro-procedure"
        val keyDeserializer = KafkaAvroDeserializer::class.java.name
        val valueDeserializer = KafkaAvroDeserializer::class.java.name
        kafkaAvroProducer.send(ProducerRecord(topic, null, struct)).get()
        val schemaRegistryUrl = KafkaEventSinkSuiteIT.schemaRegistry.getSchemaRegistryUrl()
        db.execute("""
            CALL streams.consume('$topic', {timeout: 5000, keyDeserializer: '$keyDeserializer', valueDeserializer: '$valueDeserializer', schemaRegistryUrl: '$schemaRegistryUrl'}) YIELD event
            RETURN event
        """.trimIndent()) { result ->
            assertTrue { result.hasNext() }
            val resultMap = result.next()
            assertTrue { resultMap.containsKey("event") }
            assertNotNull(resultMap["event"], "should contain event")
            val event = resultMap["event"] as Map<String, Any?>
            val resultData = event["data"] as Map<String, Any?>
            assertEquals(struct.toMap(), resultData)
        }
    }

    private fun registerProcedure() {
        db.dependencyResolver.resolveDependency(GlobalProcedures::class.java)
                .registerProcedure(StreamsSinkProcedures::class.java, true)
    }
}