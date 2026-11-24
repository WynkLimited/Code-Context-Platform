package com.wynk.codecontext.config;

import com.wynk.codecontext.port.contract.ContractExtractor;
import com.wynk.codecontext.port.contract.ContractIndexBuilder;
import com.wynk.codecontext.port.contract.ContractIndexEnricher;
import com.wynk.codecontext.port.contract.PostgresContractIndexStore;
import com.wynk.codecontext.port.contract.extract.*;
import com.wynk.codecontext.port.contract.graph.GraphSymbolBinder;
import com.wynk.codecontext.port.contract.graph.CrossRepoImpactResolver;
import com.wynk.codecontext.port.contract.compat.ContractCompatibilityEngine;
import tools.jackson.databind.json.JsonMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

/** Wires the standalone contract engine into the platform without a dependency on the reference app. */
@Configuration
public class ContractEngineConfig {
    @Bean
    ContractCompatibilityEngine contractCompatibilityEngine() { return new ContractCompatibilityEngine(); }

    @Bean
    GraphSymbolBinder graphSymbolBinder(JdbcTemplate jdbc, JsonMapper mapper) { return new GraphSymbolBinder(jdbc, mapper); }

    @Bean
    PostgresContractIndexStore postgresContractIndexStore(JdbcTemplate jdbc, JsonMapper mapper, ContractIndexBuilder builder) {
        return new PostgresContractIndexStore(jdbc, mapper, builder);
    }

    @Bean
    CrossRepoImpactResolver crossRepoImpactResolver(JdbcTemplate jdbc, PostgresContractIndexStore indexes) {
        return new CrossRepoImpactResolver(jdbc, indexes);
    }

    @Bean
    ProtoDescriptorLoader protoDescriptorLoader() { return new ProtoDescriptorLoader(); }

    @Bean
    ContractIndexBuilder contractIndexBuilder(ProtoDescriptorLoader proto, JsonMapper mapper) {
        List<ContractExtractor> extractors = List.of(
                new CacheContractExtractor(), new ConfigContractExtractor(),
                new DatabaseContractExtractor(), new EnumContractExtractor(),
                new GrpcContractExtractor(proto), new KafkaContractExtractor(),
                new MavenContractExtractor(), new ProtoContractExtractor(proto),
                new RestContractExtractor(mapper), new StringFormatContractExtractor());
        return new ContractIndexBuilder(extractors, new ContractIndexEnricher());
    }
}
