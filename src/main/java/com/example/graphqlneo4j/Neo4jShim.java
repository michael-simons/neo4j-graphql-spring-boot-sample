package com.example.graphqlneo4j;

import graphql.GraphQL;
import graphql.execution.instrumentation.ChainedInstrumentation;
import graphql.execution.instrumentation.Instrumentation;
import graphql.schema.GraphQLSchema;

import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.stream.Collectors;

import org.neo4j.driver.Driver;
import org.neo4j.driver.Session;
import org.neo4j.graphql.Cypher;
import org.neo4j.graphql.SchemaBuilder;
import org.neo4j.graphql.SchemaConfig;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.graphql.boot.GraphQLProperties;
import org.springframework.graphql.boot.MissingGraphQLSchemaException;
import org.springframework.util.FileCopyUtils;

@Configuration
public class Neo4jShim {
/* // See below
	@Bean
	@ConditionalOnMissingBean
	public RuntimeWiring runtimeWiring(ObjectProvider<RuntimeWiringCustomizer> customizers) {
		RuntimeWiring.Builder builder = RuntimeWiring.newRuntimeWiring();
		customizers.orderedStream().forEach(customizer -> customizer.customize(builder));
		return builder.build();
	}
*/

	@Bean
	public GraphQL.Builder graphQLBuilder(Driver driver, GraphQLProperties properties,
		ResourceLoader resourceLoader,
		ObjectProvider<Instrumentation> instrumentationsProvider) {
		Resource schemaResource = resourceLoader.getResource(properties.getSchemaLocation());
		GraphQLSchema schema = buildSchema(driver, schemaResource);
		GraphQL.Builder builder = GraphQL.newGraphQL(schema);
		List<Instrumentation> instrumentations = instrumentationsProvider.orderedStream().collect(Collectors.toList());
		if (!instrumentations.isEmpty()) {
			builder = builder.instrumentation(new ChainedInstrumentation(instrumentations));
		}
		return builder;
	}

	private GraphQLSchema buildSchema(Driver driver, Resource schemaResource) {

		// Cannot pass customized runtime wring https://github.com/neo4j-graphql/neo4j-graphql-java/issues/194
		// Cannot pass resource input stream https://github.com/neo4j-graphql/neo4j-graphql-java/issues/193
		try (Reader reader = new InputStreamReader(schemaResource.getInputStream(), StandardCharsets.UTF_8)) {
			String schema = FileCopyUtils.copyToString(reader);
			return SchemaBuilder.buildSchema(schema, new SchemaConfig(), (env, delegate) -> {
				Cypher cypher = delegate.get(env);
				try (Session session = driver.session()) {
					return session.writeTransaction(tx -> tx.run(cypher.getQuery(), cypher.getParams())
						.list(r -> r.get(cypher.getVariable()).asObject()));
				}
			});
		} catch (IOException e) {
			throw new MissingGraphQLSchemaException(e, schemaResource);
		}
	}
}
