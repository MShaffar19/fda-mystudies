/*
 * Copyright 2020 Google LLC
 *
 * Use of this source code is governed by an MIT-style
 * license that can be found in the LICENSE file or at
 * https://opensource.org/licenses/MIT.
 */

package com.google.cloud.healthcare.fdamystudies.oauthscim.controller;

import static com.google.cloud.healthcare.fdamystudies.common.EncryptionUtils.encrypt;
import static com.google.cloud.healthcare.fdamystudies.common.EncryptionUtils.hash;
import static com.google.cloud.healthcare.fdamystudies.common.JsonUtils.asJsonString;
import static com.google.cloud.healthcare.fdamystudies.common.JsonUtils.getTextValue;
import static com.google.cloud.healthcare.fdamystudies.common.JsonUtils.readJsonFile;
import static com.google.cloud.healthcare.fdamystudies.common.JsonUtils.toJsonNode;
import static com.google.cloud.healthcare.fdamystudies.oauthscim.common.AuthScimConstants.EXPIRES_AT;
import static com.google.cloud.healthcare.fdamystudies.oauthscim.common.AuthScimConstants.HASH;
import static com.google.cloud.healthcare.fdamystudies.oauthscim.common.AuthScimConstants.PASSWORD;
import static com.google.cloud.healthcare.fdamystudies.oauthscim.common.AuthScimConstants.PASSWORD_HISTORY;
import static com.google.cloud.healthcare.fdamystudies.oauthscim.common.AuthScimConstants.SALT;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultHandlers.print;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.google.cloud.healthcare.fdamystudies.beans.ChangePasswordRequest;
import com.google.cloud.healthcare.fdamystudies.beans.UserRequest;
import com.google.cloud.healthcare.fdamystudies.common.BaseMockIT;
import com.google.cloud.healthcare.fdamystudies.common.ErrorCode;
import com.google.cloud.healthcare.fdamystudies.common.IdGenerator;
import com.google.cloud.healthcare.fdamystudies.common.UserAccountStatus;
import com.google.cloud.healthcare.fdamystudies.oauthscim.common.ApiEndpoint;
import com.google.cloud.healthcare.fdamystudies.oauthscim.model.UserEntity;
import com.google.cloud.healthcare.fdamystudies.oauthscim.repository.UserRepository;
import com.jayway.jsonpath.JsonPath;
import java.net.MalformedURLException;
import java.util.Collections;
import org.junit.jupiter.api.MethodOrderer.OrderAnnotation;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.skyscreamer.jsonassert.JSONAssert;
import org.skyscreamer.jsonassert.JSONCompareMode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

@TestMethodOrder(OrderAnnotation.class)
public class UserControllerTest extends BaseMockIT {

  private static final String CURRENT_PASSWORD_VALUE = "M0ck!tPassword";

  private static final String NEW_PASSWORD_VALUE = "M0ck!tPassword2";

  @Autowired private UserRepository repository;

  private static String userId;

  @Test
  public void shouldReturnUnauthorized() throws Exception {
    HttpHeaders headers = getCommonHeaders();
    headers.add("Authorization", INVALID_BEARER_TOKEN);

    performPost(
        ApiEndpoint.USERS.getPath(),
        asJsonString(createUserRequest()),
        headers,
        "Invalid token",
        UNAUTHORIZED);
  }

  @Test
  public void shouldReturnBadRequestForInvalidContent() throws Exception {
    HttpHeaders headers = getCommonHeaders();
    headers.add("Authorization", VALID_BEARER_TOKEN);

    UserRequest userRequest = new UserRequest();
    userRequest.setPassword("example_password");
    userRequest.setEmail("example.com");
    MvcResult result =
        mockMvc
            .perform(
                post(ApiEndpoint.USERS.getPath())
                    .contextPath(getContextPath())
                    .content(asJsonString(userRequest))
                    .headers(headers))
            .andDo(print())
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.violations").isArray())
            .andReturn();

    String actualResponse = result.getResponse().getContentAsString();
    String expectedResponse = readJsonFile("/response/create_user_bad_request.json");
    JSONAssert.assertEquals(expectedResponse, actualResponse, JSONCompareMode.NON_EXTENSIBLE);
  }

  @Test
  @Order(1)
  public void shouldCreateANewUser() throws Exception {
    // Step-1 call API to create an user account in oauth scim database
    HttpHeaders headers = getCommonHeaders();
    headers.add("Authorization", VALID_BEARER_TOKEN);

    UserRequest request = createUserRequest();

    MvcResult result =
        mockMvc
            .perform(
                post(ApiEndpoint.USERS.getPath())
                    .contextPath(getContextPath())
                    .content(asJsonString(request))
                    .headers(headers))
            .andDo(print())
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.tempRegId").isNotEmpty())
            .andExpect(jsonPath("$.userId").isNotEmpty())
            .andReturn();

    // Step-2 Find UserEntity by userId and compare with UserRequest object
    userId = JsonPath.read(result.getResponse().getContentAsString(), "$.userId");
    UserEntity userEntity = repository.findByUserId(userId).get();
    assertNotNull(userEntity);
    assertEquals(request.getEmail(), userEntity.getEmail());

    // Step 2A- assert password and password_history fields
    JsonNode userInfo = toJsonNode(userEntity.getUserInfo());
    assertTrue(userInfo.get(PASSWORD).get(EXPIRES_AT).isLong());
    assertTrue(userInfo.get(PASSWORD_HISTORY).isArray());
  }

  @Test
  @Order(2)
  public void shouldReturnEmailExistsErrorCode() throws Exception {
    // Step-1 call API to create an user account in oauth scim database
    HttpHeaders headers = getCommonHeaders();
    headers.add("Authorization", VALID_BEARER_TOKEN);

    UserRequest request = createUserRequest();

    mockMvc
        .perform(
            post(ApiEndpoint.USERS.getPath())
                .contextPath(getContextPath())
                .content(asJsonString(request))
                .headers(headers))
        .andDo(print())
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.userId").doesNotExist())
        .andExpect(jsonPath("$.error_description").value(ErrorCode.EMAIL_EXISTS.getDescription()));
  }

  @Test
  public void shouldReturnBadRequestForChangePasswordAction()
      throws MalformedURLException, JsonProcessingException, Exception {
    HttpHeaders headers = getCommonHeaders();
    headers.add("Authorization", VALID_BEARER_TOKEN);

    ChangePasswordRequest userRequest = new ChangePasswordRequest();
    userRequest.setNewPassword("new_password");
    userRequest.setCurrentPassword("example_current_password");

    MvcResult result =
        mockMvc
            .perform(
                put(ApiEndpoint.CHANGE_PASSWORD.getPath(), IdGenerator.id())
                    .contextPath(getContextPath())
                    .content(asJsonString(userRequest))
                    .headers(headers))
            .andDo(print())
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.violations").isArray())
            .andReturn();

    String actualResponse = result.getResponse().getContentAsString();
    String expectedResponse =
        readJsonFile("/response/change_password_bad_request_response_from_annotations.json");
    JSONAssert.assertEquals(expectedResponse, actualResponse, JSONCompareMode.NON_EXTENSIBLE);
  }

  @Test
  @Order(3)
  public void shouldReturnCurrentPasswordInvalidErrorCodeForChangePasswordAction()
      throws MalformedURLException, JsonProcessingException, Exception {
    HttpHeaders headers = getCommonHeaders();
    headers.add("Authorization", VALID_BEARER_TOKEN);

    ChangePasswordRequest request = new ChangePasswordRequest();
    request.setCurrentPassword("CurrentM0ck!tPassword");
    request.setNewPassword("NewM0ck!tPassword");

    mockMvc
        .perform(
            put(ApiEndpoint.CHANGE_PASSWORD.getPath(), userId)
                .contextPath(getContextPath())
                .content(asJsonString(request))
                .headers(headers))
        .andDo(print())
        .andExpect(status().isBadRequest())
        .andExpect(
            jsonPath("$.error_description")
                .value(ErrorCode.CURRENT_PASSWORD_INVALID.getDescription()));
  }

  @Test
  public void shouldReturnUserNotFoundErrorCodeForChangePasswordAction()
      throws MalformedURLException, JsonProcessingException, Exception {
    HttpHeaders headers = getCommonHeaders();
    headers.add("Authorization", VALID_BEARER_TOKEN);

    ChangePasswordRequest request = new ChangePasswordRequest();
    request.setCurrentPassword(CURRENT_PASSWORD_VALUE);
    request.setNewPassword(NEW_PASSWORD_VALUE);

    mockMvc
        .perform(
            put(ApiEndpoint.CHANGE_PASSWORD.getPath(), IdGenerator.id())
                .contextPath(getContextPath())
                .content(asJsonString(request))
                .headers(headers))
        .andDo(print())
        .andExpect(status().isNotFound())
        .andExpect(
            jsonPath("$.error_description").value(ErrorCode.USER_NOT_FOUND.getDescription()));
  }

  @Test
  @Order(4)
  public void shouldChangeThePassword()
      throws MalformedURLException, JsonProcessingException, Exception {
    // Step-1 Call PUT method to change the password
    HttpHeaders headers = getCommonHeaders();
    headers.add("Authorization", VALID_BEARER_TOKEN);

    ChangePasswordRequest request = new ChangePasswordRequest();
    request.setCurrentPassword(CURRENT_PASSWORD_VALUE);
    request.setNewPassword(NEW_PASSWORD_VALUE);

    mockMvc
        .perform(
            put(ApiEndpoint.CHANGE_PASSWORD.getPath(), userId)
                .contextPath(getContextPath())
                .content(asJsonString(request))
                .headers(headers))
        .andDo(print())
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.message").value("Your password has been changed successfully!"));

    // Step-2 Find UserEntity by userId and then compare the password hash values
    UserEntity userEntity = repository.findByUserId(userId).get();
    assertNotNull(userEntity);

    // Step 2A- assert password hash value and password_history size
    JsonNode userInfoNode = toJsonNode(userEntity.getUserInfo());
    JsonNode passwordNode = userInfoNode.get(PASSWORD);
    String salt = getTextValue(passwordNode, SALT);
    String actualPasswordHash = getTextValue(passwordNode, HASH);
    String expectedPasswordHash = hash(encrypt(NEW_PASSWORD_VALUE, salt));

    assertEquals(expectedPasswordHash, actualPasswordHash);
    assertTrue(userInfoNode.get(PASSWORD_HISTORY).isArray());
    assertTrue(userInfoNode.get(PASSWORD_HISTORY).size() == 2);
  }

  @Test
  @Order(5)
  public void shouldReturnEnforcePasswordHistoryErrroCodeForChangePasswordAction()
      throws MalformedURLException, JsonProcessingException, Exception {
    HttpHeaders headers = getCommonHeaders();
    headers.add("Authorization", VALID_BEARER_TOKEN);

    ChangePasswordRequest request = new ChangePasswordRequest();
    request.setCurrentPassword(NEW_PASSWORD_VALUE);
    request.setNewPassword(CURRENT_PASSWORD_VALUE);

    mockMvc
        .perform(
            put(ApiEndpoint.CHANGE_PASSWORD.getPath(), userId)
                .contextPath(getContextPath())
                .content(asJsonString(request))
                .headers(headers))
        .andDo(print())
        .andExpect(status().isBadRequest())
        .andExpect(
            jsonPath("$.error_description")
                .value(ErrorCode.ENFORCE_PASSWORD_HISTORY.getDescription()));
  }

  @Test
  @Order(6)
  public void shouldDeleteTheUser() {
    // cleanup - delete the user from database
    repository.deleteByUserId(userId);
  }

  private HttpHeaders getCommonHeaders() {
    HttpHeaders headers = new HttpHeaders();
    headers.setAccept(Collections.singletonList(MediaType.APPLICATION_JSON));
    headers.setContentType(MediaType.APPLICATION_JSON);
    return headers;
  }

  private UserRequest createUserRequest() {
    UserRequest userRequest = new UserRequest();
    userRequest.setAppId("MyStudies");
    userRequest.setOrgId("FDA");
    userRequest.setEmail("mockit_oauth_scim_user@grr.la");
    userRequest.setPassword(CURRENT_PASSWORD_VALUE);
    userRequest.setStatus(UserAccountStatus.PENDING_CONFIRMATION.getStatus());
    return userRequest;
  }
}
