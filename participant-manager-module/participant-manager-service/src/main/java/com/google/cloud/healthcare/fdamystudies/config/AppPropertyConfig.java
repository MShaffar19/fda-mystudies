/*
 * Copyright 2020 Google LLC
 *
 * Use of this source code is governed by an MIT-style
 * license that can be found in the LICENSE file or at
 * https://opensource.org/licenses/MIT.
 */

package com.google.cloud.healthcare.fdamystudies.config;

import java.io.Serializable;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.stereotype.Component;

@Configuration
@Setter
@Getter
@NoArgsConstructor
@Component
public class AppPropertyConfig implements Serializable {

  private static final long serialVersionUID = -5211321032227985690L;

  @Value("${securityCodeExpireDate}")
  private String securityCodeExpireDate;

  @Value("${enrollmentTokenExpiryInHours}")
  private Integer enrollmentTokenExpiryInHours;

  @Value("${participant.invite.subject}")
  private String participantInviteSubject;

  @Value("${participant.invite.body}")
  private String participantInviteBody;

  @Value("${fromEmailAddress}")
  private String fromEmailAddress;

  @Value("${fromEmailPasswod}")
  private String fromEmailPasswod;

  @Value("${sslFactoryValue}")
  private String sslFactoryValue;

  @Value("${smtpPortValue}")
  private String smtpPortValue;

  @Value("${smtpHostName}")
  private String smtpHostName;

  @Value("${bucket.name}")
  private String bucketName;

  @Value("${authServer.ChangePasswordUrl}")
  private String authServerChangePassword;

  @Value("${auth.server.register.url}")
  private String authRegisterUrl;

  @Value("${auth.server.updateStatusUrl}")
  private String authServerUpdateStatusUrl;

  @Value("${register.user.subject}")
  private String registerUserSubject;

  @Value("${register.user.body}")
  private String registerUserBody;

  @Value("${user.details.link}")
  private String userDetailsLink;

  @Value("${org.name}")
  private String orgName;

  @Value("${update.user.subject}")
  private String updateUserSubject;

  @Value("${update.user.body}")
  private String updateUserBody;
}
