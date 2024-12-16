/*
 * Copyright 2023-2024 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.springframework.ai.vectorstore.mongodb.atlas;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import com.mongodb.client.MongoClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.ai.document.DocumentMetadata;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.core.io.DefaultResourceLoader;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mongodb.MongoDBAtlasLocalContainer;

import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.openai.OpenAiEmbeddingModel;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.core.convert.converter.Converter;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.convert.MappingMongoConverter;
import org.springframework.data.mongodb.core.convert.MongoCustomConversions;
import org.springframework.data.mongodb.core.mapping.MongoMappingContext;
import org.springframework.util.MimeType;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * @author Chris Smith
 * @author Soby Chacko
 * @author Eddú Meléndez
 * @author Thomas Vitale
 */
@Testcontainers
@EnabledIfEnvironmentVariable(named = "OPENAI_API_KEY", matches = ".+")
class MongoDBAtlasVectorStoreIT {

	@Container
	private static MongoDBAtlasLocalContainer container = new MongoDBAtlasLocalContainer(MongoDbImage.DEFAULT_IMAGE);

	private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
		.withUserConfiguration(TestApplication.class)
		.withPropertyValues("spring.data.mongodb.database=springaisample",
				String.format("spring.data.mongodb.uri=" + container.getConnectionString()));

	@BeforeEach
	public void beforeEach() {
		this.contextRunner.run(context -> {
			MongoTemplate mongoTemplate = context.getBean(MongoTemplate.class);
			mongoTemplate.getCollection("vector_store").deleteMany(new org.bson.Document());
		});
	}

	@Test
	void vectorStoreTest() {
		this.contextRunner.run(context -> {
			VectorStore vectorStore = context.getBean(VectorStore.class);

			List<Document> documents = List.of(
					new Document(
							"Spring AI rocks!! Spring AI rocks!! Spring AI rocks!! Spring AI rocks!! Spring AI rocks!!",
							Collections.singletonMap("meta1", "meta1")),
					new Document("Hello World Hello World Hello World Hello World Hello World Hello World Hello World"),
					new Document(
							"Great Depression Great Depression Great Depression Great Depression Great Depression Great Depression",
							Collections.singletonMap("meta2", "meta2")));

			vectorStore.add(documents);
			Thread.sleep(5000); // Await a second for the document to be indexed

			List<Document> results = vectorStore.similaritySearch(SearchRequest.query("Great").withTopK(1));

			assertThat(results).hasSize(1);
			Document resultDoc = results.get(0);
			assertThat(resultDoc.getId()).isEqualTo(documents.get(2).getId());
			assertThat(resultDoc.getContent()).isEqualTo(
					"Great Depression Great Depression Great Depression Great Depression Great Depression Great Depression");
			assertThat(resultDoc.getMetadata()).containsEntry("meta2", "meta2");

			// Remove all documents from the store
			vectorStore.delete(documents.stream().map(Document::getId).collect(Collectors.toList()));

			List<Document> results2 = vectorStore.similaritySearch(SearchRequest.query("Great").withTopK(1));
			assertThat(results2).isEmpty();

		});
	}

	@Test
	void documentUpdateTest() {
		this.contextRunner.run(context -> {
			VectorStore vectorStore = context.getBean(VectorStore.class);

			Document document = new Document(UUID.randomUUID().toString(), "Spring AI rocks!!",
					Collections.singletonMap("meta1", "meta1"));

			vectorStore.add(List.of(document));
			Thread.sleep(5000); // Await a second for the document to be indexed

			List<Document> results = vectorStore.similaritySearch(SearchRequest.query("Spring").withTopK(5));

			assertThat(results).hasSize(1);
			Document resultDoc = results.get(0);
			assertThat(resultDoc.getId()).isEqualTo(document.getId());
			assertThat(resultDoc.getContent()).isEqualTo("Spring AI rocks!!");
			assertThat(resultDoc.getMetadata()).containsEntry("meta1", "meta1");

			Document sameIdDocument = new Document(document.getId(),
					"The World is Big and Salvation Lurks Around the Corner",
					Collections.singletonMap("meta2", "meta2"));

			vectorStore.add(List.of(sameIdDocument));

			results = vectorStore.similaritySearch(SearchRequest.query("FooBar").withTopK(5));

			assertThat(results).hasSize(1);
			resultDoc = results.get(0);
			assertThat(resultDoc.getId()).isEqualTo(document.getId());
			assertThat(resultDoc.getContent()).isEqualTo("The World is Big and Salvation Lurks Around the Corner");
			assertThat(resultDoc.getMetadata()).containsEntry("meta2", "meta2");
		});
	}

	@Test
	void searchWithFilters() {
		this.contextRunner.run(context -> {
			VectorStore vectorStore = context.getBean(VectorStore.class);

			var bgDocument = new Document("The World is Big and Salvation Lurks Around the Corner",
					Map.of("country", "BG", "year", 2020));
			var nlDocument = new Document("The World is Big and Salvation Lurks Around the Corner",
					Map.of("country", "NL"));
			var bgDocument2 = new Document("The World is Big and Salvation Lurks Around the Corner",
					Map.of("country", "BG", "year", 2023));

			vectorStore.add(List.of(bgDocument, nlDocument, bgDocument2));
			Thread.sleep(5000); // Await a second for the document to be indexed

			List<Document> results = vectorStore.similaritySearch(SearchRequest.query("The World").withTopK(5));
			assertThat(results).hasSize(3);

			results = vectorStore.similaritySearch(SearchRequest.query("The World")
				.withTopK(5)
				.withSimilarityThresholdAll()
				.withFilterExpression("country == 'NL'"));
			assertThat(results).hasSize(1);
			assertThat(results.get(0).getId()).isEqualTo(nlDocument.getId());

			results = vectorStore.similaritySearch(SearchRequest.query("The World")
				.withTopK(5)
				.withSimilarityThresholdAll()
				.withFilterExpression("country == 'BG'"));

			assertThat(results).hasSize(2);
			assertThat(results.get(0).getId()).isIn(bgDocument.getId(), bgDocument2.getId());
			assertThat(results.get(1).getId()).isIn(bgDocument.getId(), bgDocument2.getId());

			results = vectorStore.similaritySearch(SearchRequest.query("The World")
				.withTopK(5)
				.withSimilarityThresholdAll()
				.withFilterExpression("country == 'BG' && year == 2020"));

			assertThat(results).hasSize(1);
			assertThat(results.get(0).getId()).isEqualTo(bgDocument.getId());

			results = vectorStore.similaritySearch(SearchRequest.query("The World")
				.withTopK(5)
				.withSimilarityThresholdAll()
				.withFilterExpression("NOT(country == 'BG' && year == 2020)"));

			assertThat(results).hasSize(2);
			assertThat(results.get(0).getId()).isIn(nlDocument.getId(), bgDocument2.getId());
			assertThat(results.get(1).getId()).isIn(nlDocument.getId(), bgDocument2.getId());

		});
	}

	@Test
	public void searchWithThreshold() {
		this.contextRunner.run(context -> {
			VectorStore vectorStore = context.getBean(VectorStore.class);

			var documents = List.of(
					new Document("471a8c78-549a-4b2c-bce5-ef3ae6579be3", getText("classpath:/test/data/spring.ai.txt"),
							Map.of("meta1", "meta1")),
					new Document("bc51d7f7-627b-4ba6-adf4-f0bcd1998f8f",
							getText("classpath:/test/data/time.shelter.txt"), Map.of()),
					new Document("d0237682-1150-44ff-b4d2-1be9b1731ee5",
							getText("classpath:/test/data/great.depression.txt"), Map.of("meta2", "meta2")));
			vectorStore.add(documents);
			Thread.sleep(5000); // Await a second for the document to be indexed

			List<Document> fullResult = vectorStore
				.similaritySearch(SearchRequest.query("Spring").withTopK(5).withSimilarityThresholdAll());
			assertThat(fullResult).hasSize(3);

			List<Double> scores = fullResult.stream().map(Document::getScore).toList();

			assertThat(scores).hasSize(3);

			double similarityThreshold = (scores.get(0) + scores.get(1)) / 2;

			List<Document> results = vectorStore.similaritySearch(
					SearchRequest.query("Spring").withTopK(5).withSimilarityThreshold(similarityThreshold));

			assertThat(results).hasSize(1);
			Document resultDoc = results.get(0);
			assertThat(resultDoc.getId()).isEqualTo(documents.get(0).getId());
			assertThat(resultDoc.getContent()).contains(
					"Spring AI provides abstractions that serve as the foundation for developing AI applications.");
			assertThat(resultDoc.getMetadata()).containsKeys("meta1", DocumentMetadata.DISTANCE.value());
			assertThat(resultDoc.getScore()).isGreaterThanOrEqualTo(similarityThreshold);

		});
	}

	public static String getText(String uri) {
		var resource = new DefaultResourceLoader().getResource(uri);
		try {
			return resource.getContentAsString(StandardCharsets.UTF_8);
		}
		catch (IOException e) {
			throw new RuntimeException(e);
		}
	}

	@SpringBootConfiguration
	@EnableAutoConfiguration
	public static class TestApplication {

		@Bean
		public VectorStore vectorStore(MongoTemplate mongoTemplate, EmbeddingModel embeddingModel) {
			return MongoDBAtlasVectorStore.builder()
				.mongoTemplate(mongoTemplate)
				.embeddingModel(embeddingModel)
				.metadataFieldsToFilter(List.of("country", "year"))
				.initializeSchema(true)
				.build();
		}

		@Bean
		public MongoTemplate mongoTemplate(MongoClient mongoClient, MongoCustomConversions mongoCustomConversions) {
			MongoTemplate mongoTemplate = new MongoTemplate(mongoClient, "springaisample");
			MappingMongoConverter converter = (MappingMongoConverter) mongoTemplate.getConverter();
			converter.setCustomConversions(mongoCustomConversions);
			((MongoMappingContext) converter.getMappingContext())
				.setSimpleTypeHolder(mongoCustomConversions.getSimpleTypeHolder());
			converter.afterPropertiesSet();
			return mongoTemplate;
		}

		@Bean
		public EmbeddingModel embeddingModel() {
			return new OpenAiEmbeddingModel(new OpenAiApi(System.getenv("OPENAI_API_KEY")));
		}

		@Bean
		public Converter<MimeType, String> mimeTypeToStringConverter() {
			return new Converter<MimeType, String>() {

				@Override
				public String convert(MimeType source) {
					return source.toString();
				}
			};
		}

		@Bean
		public Converter<String, MimeType> stringToMimeTypeConverter() {
			return new Converter<String, MimeType>() {

				@Override
				public MimeType convert(String source) {
					return MimeType.valueOf(source);
				}
			};
		}

		@Bean
		public MongoCustomConversions mongoCustomConversions(Converter<MimeType, String> mimeTypeToStringConverter,
				Converter<String, MimeType> stringToMimeTypeConverter) {
			return new MongoCustomConversions(Arrays.asList(mimeTypeToStringConverter, stringToMimeTypeConverter));
		}

	}

}