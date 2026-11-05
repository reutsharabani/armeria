/*
 * Copyright 2026 LY Corporation
 *
 * LY Corporation licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *   https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package com.linecorp.armeria.internal.server.grpc;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import com.linecorp.armeria.client.WebClient;
import com.linecorp.armeria.common.AggregatedHttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.server.docs.DocService;
import com.linecorp.armeria.server.grpc.GrpcService;
import com.linecorp.armeria.testing.junit5.server.ServerExtension;

import testing.grpc.TestServiceGrpc.TestServiceImplBase;

class OpenApiDocServiceTest {

    private static final ObjectMapper mapper = new ObjectMapper();

    @RegisterExtension
    static final ServerExtension server = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) throws Exception {
            sb.service(GrpcService.builder()
                                  .addService(new TestServiceImplBase() {})
                                  .enableUnframedRequests(true)
                                  .build());
            sb.serviceUnder("/docs", new DocService());
        }
    };

    @Test
    void grpcServiceIsExportedAsOpenApi() throws Exception {
        final WebClient client = WebClient.of(server.httpUri());
        final AggregatedHttpResponse response = client.get("/docs/openapi.json").aggregate().join();
        assertThat(response.status()).isEqualTo(HttpStatus.OK);

        final JsonNode spec = mapper.readTree(response.contentUtf8());
        final JsonNode unary = spec.path("paths")
                                   .path("/armeria.grpc.testing.TestService/UnaryCall")
                                   .path("post");
        final JsonNode request = spec.path("components").path("schemas")
                                     .path("armeria.grpc.testing.SimpleRequest");

        assertThat(spec.path("openapi").asText()).isEqualTo("3.0.3");
        assertThat(unary.path("requestBody").path("content").path("application/json")
                        .path("schema").path("$ref").asText())
                .isEqualTo("#/components/schemas/armeria.grpc.testing.SimpleRequest");
        assertThat(unary.path("responses").path("200").path("content").path("application/json")
                        .path("schema").path("$ref").asText())
                .isEqualTo("#/components/schemas/armeria.grpc.testing.SimpleResponse");
        assertThat(request.path("properties").path("response_size").path("type").asText())
                .isEqualTo("integer");
        assertThat(spec.path("paths").path("/armeria.grpc.testing.TestService/FullDuplexCall")
                       .has("post")).isTrue();

        final AggregatedHttpResponse swagger = client.get("/docs/swagger.html").aggregate().join();
        assertThat(swagger.status()).isEqualTo(HttpStatus.OK);
        assertThat(swagger.contentUtf8()).contains("openapi.json");
    }
}
