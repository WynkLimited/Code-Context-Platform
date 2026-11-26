package com.wynk.codecontext.config;

import io.micrometer.observation.ObservationRegistry;
import org.springframework.ai.embedding.observation.EmbeddingModelObservationConvention;
import org.springframework.ai.model.ollama.autoconfigure.OllamaEmbeddingProperties;
import org.springframework.ai.model.ollama.autoconfigure.OllamaInitializationProperties;
import org.springframework.ai.ollama.OllamaEmbeddingModel;
import org.springframework.ai.ollama.api.OllamaApi;
import org.springframework.ai.ollama.api.OllamaEmbeddingOptions;
import org.springframework.ai.ollama.management.ModelManagementOptions;
import org.springframework.ai.ollama.management.PullModelStrategy;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Spring AI 2.0.2's embedding properties do not bind numCtx; supply the provider option explicitly. */
@Configuration
@ConditionalOnProperty(name="spring.ai.model.embedding",havingValue="ollama",matchIfMissing=true)
@EnableConfigurationProperties({OllamaEmbeddingProperties.class,OllamaInitializationProperties.class})
public class OllamaEmbeddingConfiguration {
    @Bean OllamaEmbeddingModel platformEmbeddingModel(OllamaApi api, OllamaEmbeddingProperties properties,
            OllamaInitializationProperties initialization, ObjectProvider<ObservationRegistry> observations,
            ObjectProvider<EmbeddingModelObservationConvention> conventions,
            @Value("${spring.ai.ollama.embedding.num-ctx:8192}") int contextTokens) {
        if(contextTokens<512||contextTokens>131072)throw new IllegalArgumentException("embedding context must be between 512 and 131072 tokens");
        if(Boolean.TRUE.equals(properties.getTruncate()))throw new IllegalArgumentException("silent embedding truncation must remain disabled");
        var options=OllamaEmbeddingOptions.builder().model(properties.getModel()).keepAlive(properties.getKeepAlive())
                .numCtx(contextTokens).truncate(false).build();
        var initializationOptions=new ModelManagementOptions(
                initialization.getEmbedding().isInclude()?initialization.getPullModelStrategy():PullModelStrategy.NEVER,
                initialization.getEmbedding().getAdditionalModels(),initialization.getTimeout(),initialization.getMaxRetries());
        var model=new ContextAwareEmbeddingModel(api,options,observations.getIfUnique(()->ObservationRegistry.NOOP),initializationOptions);
        conventions.ifAvailable(model::setObservationConvention);
        return model;
    }
    /** Generic EMPTY options otherwise lose provider-specific defaults in Spring AI 2.0.2's merge. */
    private static final class ContextAwareEmbeddingModel extends OllamaEmbeddingModel {
        private final OllamaEmbeddingOptions defaults;
        ContextAwareEmbeddingModel(OllamaApi api,OllamaEmbeddingOptions options,ObservationRegistry observations,ModelManagementOptions initialization) {
            super(api,options,observations,initialization);this.defaults=options;
        }
        @Override public org.springframework.ai.embedding.EmbeddingResponse call(org.springframework.ai.embedding.EmbeddingRequest request) {
            var options=request.getOptions();
            if(options instanceof OllamaEmbeddingOptions ollama) {
                if(Boolean.TRUE.equals(ollama.getTruncate()))throw new IllegalArgumentException("silent embedding truncation must remain disabled");
                return super.call(request);
            }
            var typed=OllamaEmbeddingOptions.builder()
                    .model(options==null||options.getModel()==null?defaults.getModel():options.getModel())
                    .dimensions(options==null||options.getDimensions()==null?defaults.getDimensions():options.getDimensions())
                    .keepAlive(defaults.getKeepAlive()).numCtx(defaults.getNumCtx()).truncate(false).build();
            return super.call(new org.springframework.ai.embedding.EmbeddingRequest(request.getInstructions(),typed));
        }
    }

}
