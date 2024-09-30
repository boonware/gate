/*
 * Copyright 2023 Salesforce, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.netflix.spinnaker.gate.controllers;

import static com.netflix.spinnaker.kork.common.Header.REQUEST_ID;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultHandlers.print;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.webAppContextSetup;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.netflix.spinnaker.gate.Main;
import com.netflix.spinnaker.gate.health.DownstreamServicesHealthIndicator;
import com.netflix.spinnaker.gate.services.ApplicationService;
import com.netflix.spinnaker.gate.services.DefaultProviderLookupService;
import com.netflix.spinnaker.gate.services.PipelineService;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInfo;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.RequestBuilder;
import org.springframework.web.context.WebApplicationContext;

/**
 * See https://github.com/spring-projects/spring-boot/issues/5574#issuecomment-506282892 for a
 * discussion of why it's difficult to assert on response bodies generated by error handlers. It's
 * not necessary in these tests, as the information we need is available elsewhere (e.g. the http
 * status line).
 */
@SpringBootTest(classes = Main.class)
@TestPropertySource(properties = "spring.config.location=classpath:gate-test.yml")
class PipelineControllerTest {

  private MockMvc webAppMockMvc;

  @Autowired private WebApplicationContext webApplicationContext;

  @Autowired ObjectMapper objectMapper;

  @MockBean PipelineService pipelineService;

  /**
   * This takes X-SPINNAKER-* headers from requests to gate and puts them in the MDC. This is
   * enabled when gate runs normally (by GateConfig), but needs explicit mention to function in
   * these tests.
   */
  @Autowired
  @Qualifier("authenticatedRequestFilter")
  private FilterRegistrationBean filterRegistrationBean;

  /** Mock the application service to disable the background thread that caches applications */
  @MockBean ApplicationService applicationService;

  /** To prevent periodic calls to service's /health endpoints */
  @MockBean DownstreamServicesHealthIndicator downstreamServicesHealthIndicator;

  /** To prevent periodic calls to clouddriver to query for accounts */
  @MockBean DefaultProviderLookupService defaultProviderLookupService;

  private static final String APPLICATION = "my-application";
  private static final String PIPELINE_ID = "my-pipeline-id";
  private static final String SUBMITTED_REQUEST_ID = "submitted-request-id";
  private final Map<String, Object> TRIGGER = Collections.emptyMap(); // arbitrary

  @BeforeEach
  void init(TestInfo testInfo) throws JsonProcessingException {
    System.out.println("--------------- Test " + testInfo.getDisplayName());

    webAppMockMvc =
        webAppContextSetup(webApplicationContext)
            .addFilters(filterRegistrationBean.getFilter())
            .build();
  }

  @Test
  void invokePipelineConfigRuntimeException() throws Exception {
    RuntimeException exception = new RuntimeException("arbitrary message");
    when(pipelineService.trigger(anyString(), anyString(), anyMap())).thenThrow(exception);

    webAppMockMvc
        .perform(invokePipelineConfigRequest())
        .andDo(print())
        .andExpect(status().isInternalServerError())
        .andExpect(status().reason(exception.getMessage()))
        .andExpect(header().string(REQUEST_ID.getHeader(), SUBMITTED_REQUEST_ID));
  }

  /** Generate a request to the endpoint that PipelineController.invokePipelineConfig serves */
  private RequestBuilder invokePipelineConfigRequest() throws JsonProcessingException {
    return post("/pipelines/" + APPLICATION + "/" + PIPELINE_ID)
        .contentType(MediaType.APPLICATION_JSON_VALUE)
        .characterEncoding(StandardCharsets.UTF_8.toString())
        .header(REQUEST_ID.getHeader(), SUBMITTED_REQUEST_ID)
        .content(objectMapper.writeValueAsString(TRIGGER));
  }
}