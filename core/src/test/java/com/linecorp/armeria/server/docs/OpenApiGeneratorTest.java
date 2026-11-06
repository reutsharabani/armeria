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

package com.linecorp.armeria.server.docs;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;

import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.MediaType;

class OpenApiGeneratorTest {

    @Test
    void emptySpecificationIsValidOpenApi() {
        final ServiceSpecification spec = new ServiceSpecification(
                ImmutableList.of(), ImmutableList.of(), ImmutableList.of(), ImmutableList.of());

        final ObjectNode openApi = OpenApiGenerator.generate(spec);

        assertThat(openApi.path("openapi").asText()).isEqualTo("3.0.3");
        assertThat(openApi.path("paths").isObject()).isTrue();
        assertThat(openApi.path("paths").size()).isZero();
        assertThat(OpenApiGenerator.swaggerUiHtml()).contains("openapi.json");
    }

    @Test
    void grpcMethodUsesTheRequestMessageAsTheJsonBody() {
        final StructInfo request = new StructInfo("pkg.HelloRequest", ImmutableList.of(
                FieldInfo.of("name", TypeSignature.ofBase("string"))));
        final StructInfo reply = new StructInfo("pkg.HelloReply", ImmutableList.of(
                FieldInfo.of("message", TypeSignature.ofBase("string"))));
        final EndpointInfo endpoint = EndpointInfo.builder("*", "/pkg.Greeter/SayHello")
                                                  .availableMimeTypes(MediaType.JSON_UTF_8)
                                                  .build();
        final ParamInfo requestParam =
                ParamInfo.builder("request", TypeSignature.ofStruct("pkg.HelloRequest", new Object()))
                         .requirement(FieldRequirement.REQUIRED)
                         .build();
        final MethodInfo method = new MethodInfo(
                "pkg.Greeter", "SayHello",
                TypeSignature.ofStruct("pkg.HelloReply", new Object()),
                ImmutableList.of(requestParam),
                true, ImmutableList.of(), ImmutableList.of(endpoint),
                ImmutableList.of(), ImmutableList.of("{\"name\":\"Armeria\"}"),
                ImmutableList.of(), ImmutableList.of(), HttpMethod.POST);
        final ServiceSpecification spec = new ServiceSpecification(
                ImmutableList.of(new ServiceInfo("pkg.Greeter", ImmutableList.of(method))),
                ImmutableList.of(), ImmutableList.of(request, reply), ImmutableList.of(),
                ImmutableList.of(),
                ImmutableMap.of("pkg.Greeter/SayHello", DescriptionInfo.of("Says hello.")),
                null);

        final ObjectNode openApi = OpenApiGenerator.generate(spec);
        final JsonNode operation = openApi.path("paths").path("/pkg.Greeter/SayHello").path("post");
        final JsonNode body = operation.path("requestBody").path("content").path("application/json");

        assertThat(openApi.path("info").path("title").asText()).isEqualTo("pkg.Greeter");
        assertThat(operation.path("description").asText()).contains("Says hello.");
        assertThat(body.path("schema").path("$ref").asText())
                .isEqualTo("#/components/schemas/pkg.HelloRequest");
        assertThat(body.path("example").path("name").asText()).isEqualTo("Armeria");
        assertThat(operation.path("responses").path("200").path("content")
                            .path("application/json").path("schema").path("$ref").asText())
                .isEqualTo("#/components/schemas/pkg.HelloReply");
        assertThat(openApi.path("components").path("schemas").path("pkg.HelloRequest")
                          .path("properties").path("name").path("type").asText())
                .isEqualTo("string");
    }

    @Test
    void httpTranscodingKeepsMethodAndParameters() {
        final ParamInfo messageId =
                ParamInfo.builder("message_id", TypeSignature.ofBase("string"))
                         .location(FieldLocation.PATH)
                         .requirement(FieldRequirement.REQUIRED)
                         .build();
        final ParamInfo revision =
                ParamInfo.builder("revision", TypeSignature.ofBase("int64"))
                         .location(FieldLocation.QUERY)
                         .requirement(FieldRequirement.OPTIONAL)
                         .build();
        final ParamInfo text =
                ParamInfo.builder("text", TypeSignature.ofBase("string"))
                         .location(FieldLocation.BODY)
                         .requirement(FieldRequirement.OPTIONAL)
                         .build();
        final MethodInfo get = new MethodInfo(
                "pkg.Messages", "GetMessage", 0,
                TypeSignature.ofStruct("pkg.Message", new Object()),
                ImmutableList.of(messageId, revision),
                ImmutableList.of(),
                ImmutableList.of(EndpointInfo.builder("*", "exact:/v1/messages/:message_id")
                                                      .availableMimeTypes(MediaType.JSON_UTF_8)
                                                      .build()),
                HttpMethod.GET);
        final MethodInfo update = new MethodInfo(
                "pkg.Messages", "UpdateMessage", 0,
                TypeSignature.ofBase("void"),
                ImmutableList.of(messageId, text),
                ImmutableList.of(),
                ImmutableList.of(EndpointInfo.builder("*", "/v1/messages/:message_id")
                                                      .availableMimeTypes(MediaType.JSON_UTF_8)
                                                      .build()),
                HttpMethod.PATCH);
        final ServiceSpecification spec = new ServiceSpecification(
                ImmutableList.of(new ServiceInfo("pkg.Messages", ImmutableList.of(get, update))),
                ImmutableList.of(),
                ImmutableList.of(new StructInfo("pkg.Message", ImmutableList.of(
                        FieldInfo.of("text", TypeSignature.ofBase("string"))))),
                ImmutableList.of());

        final ObjectNode openApi = OpenApiGenerator.generate(spec);
        final JsonNode read = openApi.path("paths").path("/v1/messages/{message_id}").path("get");
        final JsonNode write = openApi.path("paths").path("/v1/messages/{message_id}").path("patch");

        assertThat(read.path("parameters").get(0).path("name").asText()).isEqualTo("message_id");
        assertThat(read.path("parameters").get(0).path("in").asText()).isEqualTo("path");
        assertThat(read.path("parameters").get(0).path("required").asBoolean()).isTrue();
        assertThat(read.path("parameters").get(1).path("name").asText()).isEqualTo("revision");
        assertThat(read.path("parameters").get(1).path("in").asText()).isEqualTo("query");
        assertThat(read.path("parameters").get(1).path("schema").path("format").asText()).isEqualTo("int64");
        assertThat(read.has("requestBody")).isFalse();
        assertThat(write.path("requestBody").path("content").path("application/json")
                        .path("schema").path("properties").path("text").path("type").asText())
                .isEqualTo("string");
        assertThat(write.path("responses").path("200").has("content")).isFalse();
    }

    @Test
    void unspecifiedParametersAreWrappedLikeThriftArguments() {
        final MethodInfo method = new MethodInfo(
                "pkg.ThriftHello", "hello", 0,
                TypeSignature.ofBase("string"),
                ImmutableList.of(ParamInfo.of("name", TypeSignature.ofBase("string"))),
                ImmutableList.of(),
                ImmutableList.of(EndpointInfo.builder("*", "exact:/hello")
                                                      .availableMimeTypes(MediaType.JSON_UTF_8)
                                                      .build()),
                HttpMethod.POST);
        final ServiceSpecification spec = new ServiceSpecification(
                ImmutableList.of(new ServiceInfo("pkg.ThriftHello", ImmutableList.of(method))),
                ImmutableList.of(), ImmutableList.of(), ImmutableList.of());

        final JsonNode body = OpenApiGenerator.generate(spec)
                                              .path("paths").path("/hello").path("post")
                                              .path("requestBody").path("content")
                                              .path("application/json").path("schema");

        assertThat(body.path("properties").path("name").path("type").asText()).isEqualTo("string");
    }

    @Test
    void prefixRouteBecomesARemainingPathParameter() {
        final MethodInfo method = new MethodInfo(
                "pkg.Files", "read", 0,
                TypeSignature.ofBase("bytes"),
                ImmutableList.of(),
                ImmutableList.of(),
                ImmutableList.of(EndpointInfo.builder("api.example.com", "prefix:/files")
                                                      .availableMimeTypes(MediaType.JSON_UTF_8)
                                                      .build()),
                HttpMethod.GET);
        final ServiceSpecification spec = new ServiceSpecification(
                ImmutableList.of(new ServiceInfo("pkg.Files", ImmutableList.of(method))),
                ImmutableList.of(), ImmutableList.of(), ImmutableList.of());

        final JsonNode operation = OpenApiGenerator.generate(spec)
                                                   .path("paths").path("/files/{remainingPath}").path("get");

        assertThat(operation.path("parameters").get(0).path("name").asText()).isEqualTo("remainingPath");
        assertThat(operation.path("description").asText()).contains("api.example.com");
        assertThat(operation.path("responses").path("200").path("content")
                            .path("application/json").path("schema").path("format").asText())
                .isEqualTo("byte");
    }
}
