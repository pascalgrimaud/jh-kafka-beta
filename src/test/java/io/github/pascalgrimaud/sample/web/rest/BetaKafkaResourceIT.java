package io.github.pascalgrimaud.sample.web.rest;

import io.github.pascalgrimaud.sample.config.KafkaProperties;
import io.github.pascalgrimaud.sample.web.rest.BetaKafkaResource.SafeKafkaConsumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.util.ReflectionUtils;
import org.testcontainers.containers.KafkaContainer;

import java.lang.reflect.Field;
import java.time.Duration;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class BetaKafkaResourceIT {

    private static boolean started = false;
    private static KafkaContainer kafkaContainer;

    private BetaKafkaResource kafkaResource;
    private MockMvc restMockMvc;

    @BeforeAll
    static void startServer() {
        if (!started) {
            startTestcontainer();
            started = true;
        }
    }

    private static void startTestcontainer() {
        kafkaContainer = new KafkaContainer("5.3.1");
        kafkaContainer.start();
    }

    @BeforeEach
    void setup() {
        KafkaProperties kafkaProperties = new KafkaProperties();
        Map<String, String> producerProps = getProducerProps();
        kafkaProperties.setProducer(new HashMap<>(producerProps));

        Map<String, String> consumerProps = getConsumerProps("default-group");
        consumerProps.put("client.id", "default-client");
        kafkaProperties.setConsumer(consumerProps);

        kafkaResource = new BetaKafkaResource(kafkaProperties);

        restMockMvc = MockMvcBuilders.standaloneSetup(kafkaResource)
            .build();
    }

    @Test
    void producesMessages() throws Exception {
        restMockMvc.perform(post("/api/beta-kafka/publish/topic-produce?message=value-produce"))
            .andExpect(status().isOk());

        Map<String, Object> consumerProps = new HashMap<>(getConsumerProps("group-produce"));
        KafkaConsumer<String, String> consumer = new KafkaConsumer<>(consumerProps);
        consumer.subscribe(Collections.singletonList("topic-produce"));
        ConsumerRecords<String, String> records = consumer.poll(Duration.ofSeconds(1));

        assertThat(records.count()).isEqualTo(1);
        ConsumerRecord<String, String> record = records.iterator().next();
        assertThat(record.value()).isEqualTo("value-produce");
    }

    @Test
    void createsConsumer() throws Exception {
        restMockMvc.perform(post("/api/beta-kafka/consumers?name=consumer-create&topic=topic-create&topic=topic-create2&group.id=group-create"))
            .andExpect(status().isOk());

        Field consumersField = ReflectionUtils.findField(BetaKafkaResource.class, "consumers");
        ReflectionUtils.makeAccessible(consumersField);
        Map<String, SafeKafkaConsumer> consumers = (Map<String, SafeKafkaConsumer>)consumersField.get(kafkaResource);
        assertThat(consumers).containsKey("consumer-create");
        SafeKafkaConsumer consumer = consumers.get("consumer-create");
        Field clientField = ReflectionUtils.findField(KafkaConsumer.class, "clientId");
        ReflectionUtils.makeAccessible(clientField);
        String client = (String)clientField.get(consumer);
        assertThat(client).isEqualTo("default-client");
        Field groupField = ReflectionUtils.findField(KafkaConsumer.class, "groupId");
        ReflectionUtils.makeAccessible(groupField);
        String group = (String)groupField.get(consumer);
        assertThat(group).isEqualTo("group-create");
        Set<String> subscription = consumer.subscription();
        assertThat(subscription).containsExactlyInAnyOrder("topic-create", "topic-create2");
    }

    @Test
    void consumesMessages() throws Exception {
        Map<String, Object> consumerProps = new HashMap<>(getConsumerProps("group-consume"));
        SafeKafkaConsumer consumer = new SafeKafkaConsumer(consumerProps);
        consumer.subscribe(Collections.singletonList("topic-consume"));

        Map<String, KafkaConsumer<String, String>> consumers = new HashMap<>();
        consumers.put("consumer-consume", consumer);
        Field consumersField = ReflectionUtils.findField(BetaKafkaResource.class, "consumers");
        ReflectionUtils.makeAccessible(consumersField);
        ReflectionUtils.setField(consumersField, kafkaResource, consumers);

        Map<String, Object> producerProps = new HashMap<>(getProducerProps());
        KafkaProducer<String, String> producer = new KafkaProducer<>(producerProps);

        producer.send(new ProducerRecord<>("topic-consume", "value-consume"));

        restMockMvc.perform(get("/api/beta-kafka/consumers/consumer-consume/records?durationMs=5000"))
            .andExpect(status().isOk())
            .andExpect(content().string("[\"value-consume\"]"));
    }

    private Map<String, String> getProducerProps() {
        Map<String, String> producerProps = new HashMap<>();
        producerProps.put("key.serializer", "org.apache.kafka.common.serialization.StringSerializer");
        producerProps.put("value.serializer", "org.apache.kafka.common.serialization.StringSerializer");
        producerProps.put("bootstrap.servers", kafkaContainer.getBootstrapServers());
        return producerProps;
    }

    private Map<String, String> getConsumerProps(String group) {
        Map<String, String> consumerProps = new HashMap<>();
        consumerProps.put("key.deserializer", "org.apache.kafka.common.serialization.StringDeserializer");
        consumerProps.put("value.deserializer", "org.apache.kafka.common.serialization.StringDeserializer");
        consumerProps.put("bootstrap.servers", kafkaContainer.getBootstrapServers());
        consumerProps.put("auto.offset.reset", "earliest");
        consumerProps.put("group.id", group);
        return consumerProps;
    }
}

