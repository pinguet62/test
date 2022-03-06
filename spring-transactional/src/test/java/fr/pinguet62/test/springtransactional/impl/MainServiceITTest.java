package fr.pinguet62.test.springtransactional.impl;

import fr.pinguet62.test.springtransactional.OtherService.OtherException;
import fr.pinguet62.test.springtransactional.TransactionalApplication;
import fr.pinguet62.test.springtransactional.config.MongoReplicaConfig;
import fr.pinguet62.test.springtransactional.impl.mongo.SampleDocument;
import fr.pinguet62.test.springtransactional.impl.mongo.SampleRepository;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.hamcrest.Matcher;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.mongodb.core.ReactiveMongoTemplate;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.test.EmbeddedKafkaBroker;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.kafka.test.utils.KafkaTestUtils;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static fr.pinguet62.test.springtransactional.impl.kafka.KafkaService.TOPIC;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.springframework.kafka.test.EmbeddedKafkaBroker.SPRING_EMBEDDED_KAFKA_BROKERS;

@SpringBootTest(classes = TransactionalApplication.class, properties = {
        "spring.kafka.bootstrap-servers=${" + SPRING_EMBEDDED_KAFKA_BROKERS + "}",
})
@EmbeddedKafka(topics = TOPIC, partitions = 1, brokerProperties = {
        // @Transactional
        "transaction.state.log.replication.factor=1",
        "transaction.state.log.min.isr=1",
})
@Import(MongoReplicaConfig.class)
public class MainServiceITTest {

    @Autowired
    ReactiveMongoTemplate mongoTemplate;
    @Autowired
    SampleRepository repository;
    @Autowired
    EmbeddedKafkaBroker embeddedKafkaBroker;

    @Autowired
    MainService service;

    @BeforeEach
    void clearMongoAndVerifyKafka() {
        // required by Mongo sessions
        mongoTemplate.collectionExists(SampleDocument.class)
                .flatMap(exists -> exists ? Mono.empty() : mongoTemplate.createCollection(SampleDocument.class))
                .block();
        repository.deleteAll().block();
        assertKafkaRecordsCount(is(0));
    }

    @Test
    void mongo() throws Exception {
        StepVerifier.create(service.processMongo("test"))
                .verifyError(OtherException.class);

        Thread.sleep(1_000);
        assertMongoRepositoryCount(is(0L));
    }

    @Test
    void kafka() throws Exception {
        StepVerifier.create(service.processKafka("test"))
                .verifyError(OtherException.class);

        Thread.sleep(1_000);
        assertKafkaRecordsCount(is(0));
    }

    @Test
    void all() throws Exception {
        StepVerifier.create(service.processAll("test"))
                .verifyError(OtherException.class);

        Thread.sleep(1_000);
        assertMongoRepositoryCount(is(0L));
        assertKafkaRecordsCount(is(0));
    }

    void assertMongoRepositoryCount(Matcher<Long> matcher) {
        assertThat(repository.count().block(), matcher);
    }

    void assertKafkaRecordsCount(Matcher<Integer> matcher) {
        Map<String, Object> props = KafkaTestUtils.consumerProps(UUID.randomUUID().toString(), "false", embeddedKafkaBroker);
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        props.put(ConsumerConfig.ISOLATION_LEVEL_CONFIG, "read_committed");
        Consumer<Integer, String> consumer = new DefaultKafkaConsumerFactory<Integer, String>(props)
                .createConsumer();
        consumer.subscribe(List.of(TOPIC));
        assertThat(KafkaTestUtils.getRecords(consumer, 1000).count(), matcher);
    }
}
