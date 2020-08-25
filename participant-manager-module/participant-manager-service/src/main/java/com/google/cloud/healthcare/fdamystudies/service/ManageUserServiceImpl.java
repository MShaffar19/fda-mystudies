/*
 * Copyright 2020 Google LLC
 *
 * Use of this source code is governed by an MIT-style
 * license that can be found in the LICENSE file or at
 * https://opensource.org/licenses/MIT.
 */

package com.google.cloud.healthcare.fdamystudies.service;

import com.google.cloud.healthcare.fdamystudies.beans.AdminUserResponse;
import com.google.cloud.healthcare.fdamystudies.beans.AuditLogEventRequest;
import com.google.cloud.healthcare.fdamystudies.beans.EmailRequest;
import com.google.cloud.healthcare.fdamystudies.beans.EmailResponse;
import com.google.cloud.healthcare.fdamystudies.beans.GetAdminDetailsResponse;
import com.google.cloud.healthcare.fdamystudies.beans.GetUsersResponse;
import com.google.cloud.healthcare.fdamystudies.beans.User;
import com.google.cloud.healthcare.fdamystudies.beans.UserAppDetails;
import com.google.cloud.healthcare.fdamystudies.beans.UserAppPermissionRequest;
import com.google.cloud.healthcare.fdamystudies.beans.UserRequest;
import com.google.cloud.healthcare.fdamystudies.beans.UserSiteDetails;
import com.google.cloud.healthcare.fdamystudies.beans.UserSitePermissionRequest;
import com.google.cloud.healthcare.fdamystudies.beans.UserStudyDetails;
import com.google.cloud.healthcare.fdamystudies.beans.UserStudyPermissionRequest;
import com.google.cloud.healthcare.fdamystudies.common.CommonConstants;
import com.google.cloud.healthcare.fdamystudies.common.ErrorCode;
import com.google.cloud.healthcare.fdamystudies.common.MessageCode;
import com.google.cloud.healthcare.fdamystudies.common.ParticipantManagerAuditLogHelper;
import com.google.cloud.healthcare.fdamystudies.common.ParticipantManagerEvent;
import com.google.cloud.healthcare.fdamystudies.common.Permission;
import com.google.cloud.healthcare.fdamystudies.config.AppPropertyConfig;
import com.google.cloud.healthcare.fdamystudies.mapper.UserMapper;
import com.google.cloud.healthcare.fdamystudies.model.AppEntity;
import com.google.cloud.healthcare.fdamystudies.model.AppPermissionEntity;
import com.google.cloud.healthcare.fdamystudies.model.SiteEntity;
import com.google.cloud.healthcare.fdamystudies.model.SitePermissionEntity;
import com.google.cloud.healthcare.fdamystudies.model.StudyEntity;
import com.google.cloud.healthcare.fdamystudies.model.StudyPermissionEntity;
import com.google.cloud.healthcare.fdamystudies.model.UserRegAdminEntity;
import com.google.cloud.healthcare.fdamystudies.repository.AppPermissionRepository;
import com.google.cloud.healthcare.fdamystudies.repository.AppRepository;
import com.google.cloud.healthcare.fdamystudies.repository.SitePermissionRepository;
import com.google.cloud.healthcare.fdamystudies.repository.SiteRepository;
import com.google.cloud.healthcare.fdamystudies.repository.StudyPermissionRepository;
import com.google.cloud.healthcare.fdamystudies.repository.StudyRepository;
import com.google.cloud.healthcare.fdamystudies.repository.UserRegAdminRepository;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.apache.commons.collections4.CollectionUtils;
import org.slf4j.ext.XLogger;
import org.slf4j.ext.XLoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ManageUserServiceImpl implements ManageUserService {

  private XLogger logger = XLoggerFactory.getXLogger(ManageUserServiceImpl.class.getName());

  @Autowired private UserRegAdminRepository userAdminRepository;

  @Autowired private AppRepository appRepository;

  @Autowired private StudyRepository studyRepository;

  @Autowired private SiteRepository siteRepository;

  @Autowired private AppPermissionRepository appPermissionRepository;

  @Autowired private StudyPermissionRepository studyPermissionRepository;

  @Autowired private SitePermissionRepository sitePermissionRepository;

  @Autowired private AppPropertyConfig appConfig;

  @Autowired private EmailService emailService;

  @Autowired private ParticipantManagerAuditLogHelper participantManagerHelper;

  @Override
  @Transactional
  public AdminUserResponse createUser(UserRequest user, AuditLogEventRequest auditRequest) {
    logger.entry(String.format("createUser() with isSuperAdmin=%b", user.isSuperAdmin()));
    ErrorCode errorCode = validateUserRequest(user);
    if (errorCode != null) {
      logger.exit(String.format(CommonConstants.ERROR_CODE_LOG, errorCode));
      return new AdminUserResponse(errorCode);
    }

    AdminUserResponse userResponse =
        user.isSuperAdmin()
            ? saveSuperAdminDetails(user, auditRequest)
            : saveAdminDetails(user, auditRequest);

    String accessLevel = user.isSuperAdmin() ? CommonConstants.SUPER_ADMIN : CommonConstants.ADMIN;
    if (userResponse.getUserId() != null) {
      Map<String, String> map =
          Stream.of(
                  new String[][] {
                    {"new_user_id", userResponse.getUserId()},
                    {"new_user_access_level", accessLevel}
                  })
              .collect(Collectors.toMap(data -> data[0], data -> data[1]));
      participantManagerHelper.logEvent(
          ParticipantManagerEvent.NEW_USER_CREATED, auditRequest, map);
    }

    logger.exit(String.format(CommonConstants.STATUS_LOG, userResponse.getHttpStatusCode()));
    return userResponse;
  }

  private EmailResponse sendInvitationEmail(UserRequest user, String securityCode) {
    Map<String, String> templateArgs = new HashMap<>();
    templateArgs.put("ORG_NAME", appConfig.getOrgName());
    templateArgs.put("FIRST_NAME", user.getFirstName());
    templateArgs.put("ACTIVATION_LINK", appConfig.getUserDetailsLink() + securityCode);
    templateArgs.put("CONTACT_EMAIL_ADDRESS", appConfig.getFromEmailAddress());
    EmailRequest emailRequest =
        new EmailRequest(
            appConfig.getFromEmailAddress(),
            new String[] {user.getEmail()},
            null,
            null,
            appConfig.getRegisterUserSubject(),
            appConfig.getRegisterUserBody(),
            templateArgs);
    return emailService.sendMimeMail(emailRequest);
  }

  private ErrorCode validateUserRequest(UserRequest user) {
    logger.entry("validateUserRequest()");
    Optional<UserRegAdminEntity> optAdminDetails =
        userAdminRepository.findById(user.getSuperAdminUserId());
    if (!optAdminDetails.isPresent()) {
      return ErrorCode.USER_NOT_FOUND;
    }

    UserRegAdminEntity loggedInUserDetails = optAdminDetails.get();
    if (!loggedInUserDetails.isSuperAdmin()) {
      return ErrorCode.NOT_SUPER_ADMIN_ACCESS;
    }

    if (!user.isSuperAdmin()
        && (CollectionUtils.isEmpty(user.getApps()) || !hasAtleastOnePermission(user))) {
      return ErrorCode.PERMISSION_MISSING;
    }

    Optional<UserRegAdminEntity> optUsers = userAdminRepository.findByEmail(user.getEmail());
    logger.exit("Successfully validated user request");
    return optUsers.isPresent() ? ErrorCode.EMAIL_EXISTS : null;
  }

  private boolean hasAtleastOnePermission(UserRequest user) {
    logger.entry("hasAtleastOnePermission()");
    Predicate<UserAppPermissionRequest> appPredicate = app -> app.isSelected();
    Predicate<UserStudyPermissionRequest> studyPredicate = study -> study.isSelected();
    Predicate<UserSitePermissionRequest> sitePredicate = site -> site.isSelected();

    List<UserAppPermissionRequest> selectedApps =
        user.getApps().stream().filter(appPredicate).collect(Collectors.toList());
    if (CollectionUtils.isNotEmpty(selectedApps)) {
      return true;
    }

    for (UserAppPermissionRequest appPermission : user.getApps()) {
      List<UserStudyPermissionRequest> selectedStudies =
          CollectionUtils.emptyIfNull(appPermission.getStudies())
              .stream()
              .filter(studyPredicate)
              .collect(Collectors.toList());
      if (CollectionUtils.isNotEmpty(selectedStudies)) {
        return true;
      }

      for (UserStudyPermissionRequest studyPermission : appPermission.getStudies()) {
        List<UserSitePermissionRequest> selectedSites =
            CollectionUtils.emptyIfNull(studyPermission.getSites())
                .stream()
                .filter(sitePredicate)
                .collect(Collectors.toList());
        if (CollectionUtils.isNotEmpty(selectedSites)) {
          return true;
        }
      }
    }

    logger.exit("No permissions found, return false");
    return false;
  }

  private AdminUserResponse saveAdminDetails(UserRequest user, AuditLogEventRequest auditRequest) {
    logger.entry("saveAdminDetails()");
    UserRegAdminEntity adminDetails =
        UserMapper.fromUserRequest(user, Long.valueOf(appConfig.getSecurityCodeExpireDate()));
    adminDetails = userAdminRepository.saveAndFlush(adminDetails);

    Map<Boolean, List<UserAppPermissionRequest>> groupBySelectedAppMap =
        user.getApps()
            .stream()
            .collect(Collectors.groupingBy(UserAppPermissionRequest::isSelected));

    // save permissions for selected apps
    for (UserAppPermissionRequest app :
        CollectionUtils.emptyIfNull(groupBySelectedAppMap.get(CommonConstants.SELECTED))) {
      saveAppStudySitePermissions(user, adminDetails, app);
    }

    // save permissions for unselected apps
    for (UserAppPermissionRequest app :
        CollectionUtils.emptyIfNull(groupBySelectedAppMap.get(CommonConstants.UNSELECTED))) {
      for (UserStudyPermissionRequest study : CollectionUtils.emptyIfNull(app.getStudies())) {
        if (study.isSelected()) {
          saveStudySitePermissions(user, adminDetails, study);
        } else if (CollectionUtils.isNotEmpty(study.getSites())) {
          saveSitePermissions(user, adminDetails, study);
        }
      }
    }

    EmailResponse emailResponse = sendInvitationEmail(user, adminDetails.getSecurityCode());
    logger.debug(
        String.format("send add new user email status=%s", emailResponse.getHttpStatusCode()));

    if (MessageCode.EMAIL_ACCEPTED_BY_MAIL_SERVER.getMessage().equals(emailResponse.getMessage())) {
      Map<String, String> map =
          Stream.of(new String[][] {{"new_user_id", adminDetails.getId()}})
              .collect(Collectors.toMap(data -> data[0], data -> data[1]));
      participantManagerHelper.logEvent(
          ParticipantManagerEvent.NEW_USER_INVITATION_EMAIL_SENT, auditRequest, map);
    } else {
      Map<String, String> map =
          Stream.of(new String[][] {{"new_user_id", adminDetails.getId()}})
              .collect(Collectors.toMap(data -> data[0], data -> data[1]));
      participantManagerHelper.logEvent(
          ParticipantManagerEvent.NEW_USER_INVITATION_EMAIL_FAILED, auditRequest, map);
    }

    logger.exit("Successfully saved admin details.");
    return new AdminUserResponse(MessageCode.ADD_NEW_USER_SUCCESS, adminDetails.getId());
  }

  private void saveAppStudySitePermissions(
      UserRequest user, UserRegAdminEntity adminDetails, UserAppPermissionRequest app) {
    logger.entry("saveAppStudySitePermissions()");
    Optional<AppEntity> optApp = appRepository.findById(app.getId());
    if (!optApp.isPresent()) {
      return;
    }

    AppEntity appDetails = optApp.get();
    AppPermissionEntity appPermission =
        UserMapper.newAppPermissionEntity(user, adminDetails, app, appDetails);
    appPermissionRepository.saveAndFlush(appPermission);

    List<StudyEntity> studies =
        (List<StudyEntity>) CollectionUtils.emptyIfNull(studyRepository.findByAppId(app.getId()));

    List<StudyPermissionEntity> studyPermissions =
        UserMapper.newStudyPermissionList(user, adminDetails, app, appDetails, studies);
    studyPermissionRepository.saveAll(studyPermissions);

    List<String> studyIds = studies.stream().map(StudyEntity::getId).collect(Collectors.toList());

    List<SiteEntity> sites =
        (List<SiteEntity>) CollectionUtils.emptyIfNull(siteRepository.findByStudyIds(studyIds));

    List<SitePermissionEntity> sitePermissions =
        UserMapper.newSitePermissionList(user, adminDetails, app, appDetails, sites);
    sitePermissionRepository.saveAll(sitePermissions);

    logger.exit("Successfully saved app study and site permissions.");
  }

  private void saveStudySitePermissions(
      UserRequest userId, UserRegAdminEntity superAdminDetails, UserStudyPermissionRequest study) {
    logger.entry("saveStudySitePermissions()");
    Optional<StudyEntity> optStudyInfo = studyRepository.findById(study.getStudyId());
    if (!optStudyInfo.isPresent()) {
      return;
    }
    List<SiteEntity> sites = siteRepository.findAll();
    StudyEntity studyDetails = optStudyInfo.get();
    StudyPermissionEntity studyPermission =
        UserMapper.newStudyPermissionEntity(userId, superAdminDetails, study, studyDetails);
    studyPermissionRepository.saveAndFlush(studyPermission);
    if (CollectionUtils.isNotEmpty(sites)) {
      for (SiteEntity site : sites) {
        if (site.getStudy().getId().equals(study.getStudyId())) {
          SitePermissionEntity sitePermission =
              UserMapper.newSitePermissionEntity(
                  userId, superAdminDetails, study, studyDetails, site);
          sitePermissionRepository.saveAndFlush(sitePermission);
        }
      }
    }
    logger.exit("Successfully saved study and site permissions.");
  }

  private void saveSitePermissions(
      UserRequest user, UserRegAdminEntity superAdminDetails, UserStudyPermissionRequest study) {
    for (UserSitePermissionRequest site : study.getSites()) {
      logger.entry("saveSitePermission()");
      if (site.isSelected()) {
        Optional<SiteEntity> optSite = siteRepository.findById(site.getSiteId());
        if (!optSite.isPresent()) {
          return;
        }
        SiteEntity siteDetails = optSite.get();
        SitePermissionEntity sitePermission =
            UserMapper.newSitePermissionEntity(user, site, superAdminDetails, siteDetails);
        sitePermissionRepository.saveAndFlush(sitePermission);
      }
    }
    logger.exit("Successfully saved site permissions.");
  }

  private AdminUserResponse saveSuperAdminDetails(
      UserRequest user, AuditLogEventRequest auditRequest) {
    logger.entry("saveSuperAdminDetails()");
    UserRegAdminEntity superAdminDetails =
        UserMapper.fromUserRequest(user, Long.valueOf(appConfig.getSecurityCodeExpireDate()));

    List<AppPermissionEntity> appPermissions =
        getAppPermissisonsForSuperAdmin(user, superAdminDetails);
    superAdminDetails.getAppPermissions().addAll(appPermissions);

    List<StudyPermissionEntity> studyPermissions =
        getStudyPermissisonsForSuperAdmin(user, superAdminDetails);
    superAdminDetails.getStudyPermissions().addAll(studyPermissions);

    List<SitePermissionEntity> sitePermissions =
        getSitePermissisonsForSuperAdmin(user, superAdminDetails);
    superAdminDetails.getSitePermissions().addAll(sitePermissions);

    superAdminDetails = userAdminRepository.saveAndFlush(superAdminDetails);

    EmailResponse emailResponse = sendInvitationEmail(user, superAdminDetails.getSecurityCode());
    logger.debug(
        String.format("send add new user email status=%s", emailResponse.getHttpStatusCode()));

    if (MessageCode.EMAIL_ACCEPTED_BY_MAIL_SERVER.getMessage().equals(emailResponse.getMessage())) {
      Map<String, String> map =
          Stream.of(new String[][] {{"new_user_id", superAdminDetails.getId()}})
              .collect(Collectors.toMap(data -> data[0], data -> data[1]));
      participantManagerHelper.logEvent(
          ParticipantManagerEvent.NEW_USER_INVITATION_EMAIL_SENT, auditRequest, map);
    } else {
      Map<String, String> map =
          Stream.of(new String[][] {{"new_user_id", superAdminDetails.getId()}})
              .collect(Collectors.toMap(data -> data[0], data -> data[1]));
      participantManagerHelper.logEvent(
          ParticipantManagerEvent.NEW_USER_INVITATION_EMAIL_FAILED, auditRequest, map);
    }

    logger.exit(String.format(CommonConstants.MESSAGE_CODE_LOG, MessageCode.ADD_NEW_USER_SUCCESS));
    return new AdminUserResponse(MessageCode.ADD_NEW_USER_SUCCESS, superAdminDetails.getId());
  }

  private List<SitePermissionEntity> getSitePermissisonsForSuperAdmin(
      UserRequest user, UserRegAdminEntity superAdminDetails) {
    logger.entry("getSitePermissisonsForSuperAdmin()");
    List<SiteEntity> sites =
        (List<SiteEntity>) CollectionUtils.emptyIfNull(siteRepository.findAll());
    List<SitePermissionEntity> sitePermissions = new ArrayList<>();
    for (SiteEntity siteInfo : sites) {
      SitePermissionEntity sitePermission =
          UserMapper.newSitePermissionEntity(user, null, superAdminDetails, siteInfo);
      sitePermissions.add(sitePermission);
    }

    logger.exit(String.format("total site permissions=%d", sitePermissions.size()));
    return sitePermissions;
  }

  private List<StudyPermissionEntity> getStudyPermissisonsForSuperAdmin(
      UserRequest user, UserRegAdminEntity superAdminDetails) {
    logger.entry("getStudyPermissisonsForSuperAdmin()");
    List<StudyEntity> studies =
        (List<StudyEntity>) CollectionUtils.emptyIfNull(studyRepository.findAll());
    List<StudyPermissionEntity> studyPermissions = new ArrayList<>();
    for (StudyEntity studyInfo : studies) {
      StudyPermissionEntity studyPermission =
          UserMapper.newStudyPermissionEntity(user, superAdminDetails, null, studyInfo);
      studyPermissions.add(studyPermission);
    }

    logger.exit(String.format("total study permissions=%d", studyPermissions.size()));
    return studyPermissions;
  }

  private List<AppPermissionEntity> getAppPermissisonsForSuperAdmin(
      UserRequest user, UserRegAdminEntity superAdminDetails) {
    logger.entry("getAppPermissisonsForSuperAdmin()");
    List<AppEntity> apps = (List<AppEntity>) CollectionUtils.emptyIfNull(appRepository.findAll());
    List<AppPermissionEntity> appPermissions = new ArrayList<>();
    for (AppEntity app : apps) {
      AppPermissionEntity appPermission =
          UserMapper.newAppPermissionEntity(user, superAdminDetails, app);
      appPermissions.add(appPermission);
    }

    logger.exit(String.format("total app permissions=%d", appPermissions.size()));
    return appPermissions;
  }

  @Override
  @Transactional
  public AdminUserResponse updateUser(
      UserRequest user, String superAdminUserId, AuditLogEventRequest auditRequest) {
    logger.entry(String.format("updateUser() with isSuperAdmin=%b", user.isSuperAdmin()));
    ErrorCode errorCode = validateUpdateUserRequest(user, superAdminUserId);
    if (errorCode != null) {
      logger.exit(String.format(CommonConstants.ERROR_CODE_LOG, errorCode));
      return new AdminUserResponse(errorCode);
    }

    AdminUserResponse userResponse =
        user.isSuperAdmin()
            ? updateSuperAdminDetails(user, superAdminUserId, auditRequest)
            : updateAdminDetails(user, superAdminUserId, auditRequest);
    String accessLevel = user.isSuperAdmin() ? CommonConstants.SUPER_ADMIN : CommonConstants.ADMIN;
    if (MessageCode.UPDATE_USER_SUCCESS.getMessage().equals(userResponse.getMessage())) {
      Map<String, String> map =
          Stream.of(
                  new String[][] {
                    {"edited_user_id", user.getUserId()}, {"new_user_access_level", accessLevel}
                  })
              .collect(Collectors.toMap(data -> data[0], data -> data[1]));
      participantManagerHelper.logEvent(
          ParticipantManagerEvent.USER_RECORD_UPDATED, auditRequest, map);
    }

    logger.exit(String.format(CommonConstants.STATUS_LOG, userResponse.getHttpStatusCode()));
    return userResponse;
  }

  private ErrorCode validateUpdateUserRequest(UserRequest user, String superAdminUserId) {
    logger.entry("validateUpdateUserRequest()");
    Optional<UserRegAdminEntity> optAdminDetails = userAdminRepository.findById(superAdminUserId);
    if (!optAdminDetails.isPresent() || user.getUserId() == null) {
      return ErrorCode.USER_NOT_FOUND;
    }

    UserRegAdminEntity loggedInUserDetails = optAdminDetails.get();
    if (!loggedInUserDetails.isSuperAdmin()) {
      return ErrorCode.NOT_SUPER_ADMIN_ACCESS;
    }

    if (!user.isSuperAdmin() && !hasAtleastOnePermission(user)) {
      return ErrorCode.PERMISSION_MISSING;
    }
    logger.exit("Successfully validated user request");
    return null;
  }

  private AdminUserResponse updateSuperAdminDetails(
      UserRequest user, String superAdminUserId, AuditLogEventRequest auditRequest) {
    logger.entry("updateSuperAdminDetails()");
    Optional<UserRegAdminEntity> optAdminDetails = userAdminRepository.findById(user.getUserId());

    if (!optAdminDetails.isPresent()) {
      return new AdminUserResponse(ErrorCode.USER_NOT_FOUND);
    }

    UserRegAdminEntity adminDetails = optAdminDetails.get();
    adminDetails = UserMapper.fromUpdateUserRequest(user, adminDetails);

    deleteAllPermissions(user.getUserId());

    user.setSuperAdminUserId(superAdminUserId);

    List<AppPermissionEntity> appPermissions = getAppPermissisonsForSuperAdmin(user, adminDetails);
    adminDetails.getAppPermissions().addAll(appPermissions);

    List<StudyPermissionEntity> studyPermissions =
        getStudyPermissisonsForSuperAdmin(user, adminDetails);
    adminDetails.getStudyPermissions().addAll(studyPermissions);

    List<SitePermissionEntity> sitePermissions =
        getSitePermissisonsForSuperAdmin(user, adminDetails);
    adminDetails.getSitePermissions().addAll(sitePermissions);

    userAdminRepository.saveAndFlush(adminDetails);

    EmailResponse emailResponse = sendUserUpdatedEmail(user);
    logger.debug(String.format("send update email status=%s", emailResponse.getHttpStatusCode()));

    if (MessageCode.EMAIL_ACCEPTED_BY_MAIL_SERVER.getMessage().equals(emailResponse.getMessage())) {
      Map<String, String> map =
          Stream.of(new String[][] {{"edited_user_id", adminDetails.getId()}})
              .collect(Collectors.toMap(data -> data[0], data -> data[1]));
      participantManagerHelper.logEvent(
          ParticipantManagerEvent.ACCOUNT_UPDATE_EMAIL_SENT, auditRequest, map);
    } else {
      Map<String, String> map =
          Stream.of(new String[][] {{"edited_user_id", adminDetails.getId()}})
              .collect(Collectors.toMap(data -> data[0], data -> data[1]));
      participantManagerHelper.logEvent(
          ParticipantManagerEvent.ACCOUNT_UPDATE_EMAIL_FAILED, auditRequest, map);
    }

    logger.exit(String.format(CommonConstants.MESSAGE_CODE_LOG, MessageCode.UPDATE_USER_SUCCESS));
    return new AdminUserResponse(MessageCode.UPDATE_USER_SUCCESS, adminDetails.getId());
  }

  private EmailResponse sendUserUpdatedEmail(UserRequest user) {
    Map<String, String> templateArgs = new HashMap<>();
    templateArgs.put("ORG_NAME", appConfig.getOrgName());
    templateArgs.put("FIRST_NAME", user.getFirstName());
    templateArgs.put("CONTACT_EMAIL_ADDRESS", appConfig.getFromEmailAddress());
    EmailRequest emailRequest =
        new EmailRequest(
            appConfig.getFromEmailAddress(),
            new String[] {user.getEmail()},
            null,
            null,
            appConfig.getUpdateUserSubject(),
            appConfig.getUpdateUserBody(),
            templateArgs);
    return emailService.sendMimeMail(emailRequest);
  }

  private AdminUserResponse updateAdminDetails(
      UserRequest user, String superAdminUserId, AuditLogEventRequest auditRequest) {
    logger.entry("updateAdminDetails()");

    Optional<UserRegAdminEntity> optAdminDetails = userAdminRepository.findById(user.getUserId());

    if (!optAdminDetails.isPresent()) {
      return new AdminUserResponse(ErrorCode.USER_NOT_FOUND);
    }

    UserRegAdminEntity adminDetails = optAdminDetails.get();
    adminDetails = UserMapper.fromUpdateUserRequest(user, adminDetails);
    userAdminRepository.saveAndFlush(adminDetails);

    deleteAllPermissions(user.getUserId());

    user.setSuperAdminUserId(superAdminUserId);

    Map<Boolean, List<UserAppPermissionRequest>> groupBySelectedAppMap =
        user.getApps()
            .stream()
            .collect(Collectors.groupingBy(UserAppPermissionRequest::isSelected));

    // save permissions for selected apps
    for (UserAppPermissionRequest app :
        CollectionUtils.emptyIfNull(groupBySelectedAppMap.get(CommonConstants.SELECTED))) {
      saveAppStudySitePermissions(user, adminDetails, app);
    }

    // save permissions for unselected apps
    for (UserAppPermissionRequest app :
        CollectionUtils.emptyIfNull(groupBySelectedAppMap.get(CommonConstants.UNSELECTED))) {
      for (UserStudyPermissionRequest study : CollectionUtils.emptyIfNull(app.getStudies())) {
        if (study.isSelected()) {
          saveStudySitePermissions(user, adminDetails, study);
        } else if (CollectionUtils.isNotEmpty(study.getSites())) {
          saveSitePermissions(user, adminDetails, study);
        }
      }
    }

    EmailResponse emailResponse = sendUserUpdatedEmail(user);
    logger.debug(String.format("send update email status=%s", emailResponse.getHttpStatusCode()));

    if (MessageCode.EMAIL_ACCEPTED_BY_MAIL_SERVER.getMessage().equals(emailResponse.getMessage())) {
      Map<String, String> map =
          Stream.of(new String[][] {{"edited_user_id", adminDetails.getId()}})
              .collect(Collectors.toMap(data -> data[0], data -> data[1]));
      participantManagerHelper.logEvent(
          ParticipantManagerEvent.ACCOUNT_UPDATE_EMAIL_SENT, auditRequest, map);
    } else {
      Map<String, String> map =
          Stream.of(new String[][] {{"edited_user_id", adminDetails.getId()}})
              .collect(Collectors.toMap(data -> data[0], data -> data[1]));
      participantManagerHelper.logEvent(
          ParticipantManagerEvent.ACCOUNT_UPDATE_EMAIL_FAILED, auditRequest, map);
    }

    logger.exit("Successfully updated admin details.");
    return new AdminUserResponse(MessageCode.UPDATE_USER_SUCCESS, adminDetails.getId());
  }

  private void deleteAllPermissions(String userId) {
    logger.entry("deleteAllPermissions()");
    sitePermissionRepository.deleteByAdminUserId(userId);
    studyPermissionRepository.deleteByAdminUserId(userId);
    appPermissionRepository.deleteByAdminUserId(userId);
    logger.exit("Successfully deleted all the assigned permissions.");
  }

  @Override
  public GetAdminDetailsResponse getAdminDetails(String userId, String adminId) {
    logger.entry("getAdminDetails()");
    ErrorCode errorCode = validateUserRequest(userId);
    if (errorCode != null) {
      logger.error(errorCode.toString());
      return new GetAdminDetailsResponse(errorCode);
    }

    Optional<UserRegAdminEntity> optAdminDetails = userAdminRepository.findById(adminId);
    if (!optAdminDetails.isPresent()) {
      logger.error(ErrorCode.ADMIN_NOT_FOUND.toString());
      return new GetAdminDetailsResponse(ErrorCode.ADMIN_NOT_FOUND);
    }

    UserRegAdminEntity adminDetails = optAdminDetails.get();
    User user = UserMapper.prepareUserInfo(adminDetails);
    List<AppEntity> apps = appRepository.findAll();
    List<AppPermissionEntity> appPermissions =
        appPermissionRepository.findByAdminUserId(user.getId());

    Map<String, AppPermissionEntity> appPermissionMap =
        appPermissions
            .stream()
            .collect(Collectors.toMap(AppPermissionEntity::getAppId, Function.identity()));

    for (AppEntity app : apps) {
      UserAppDetails userAppBean = UserMapper.toUserAppDetails(app);
      AppPermissionEntity appPermission = appPermissionMap.get(app.getId());
      if (appPermission != null && appPermission.getEdit() != null) {
        Permission permission = Permission.fromValue(appPermission.getEdit());
        userAppBean.setPermission(permission.value());
        if (Permission.NO_PERMISSION != permission) {
          userAppBean.setSelected(true);
        }
      } else if (adminDetails.isSuperAdmin()) {
        userAppBean.setPermission(Permission.READ_EDIT.value());
        userAppBean.setSelected(true);
      }

      List<UserStudyDetails> userStudies = getUserStudies(app, adminDetails);
      userAppBean.getStudies().addAll(userStudies);

      setStudiesSitesCountPerApp(userAppBean, userStudies);

      user.getApps().add(userAppBean);
    }

    logger.exit(
        String.format(
            "total apps=%d, superadmin=%b, status=%s",
            user.getApps().size(), user.isSuperAdmin(), user.getStatus()));
    return new GetAdminDetailsResponse(MessageCode.GET_ADMIN_DETAILS_SUCCESS, user);
  }

  private void setStudiesSitesCountPerApp(
      UserAppDetails userAppBean, List<UserStudyDetails> userStudies) {
    int selectedStudiesCount =
        (int) userStudies.stream().filter(UserStudyDetails::isSelected).count();
    userAppBean.setSelectedStudiesCount(selectedStudiesCount);
    userAppBean.setTotalStudiesCount(userStudies.size());

    int selectedSitesCountPerApp =
        userStudies.stream().mapToInt(UserStudyDetails::getSelectedSitesCount).sum();
    userAppBean.setSelectedSitesCount(selectedSitesCountPerApp);

    int totalSitesCount =
        userStudies.stream().map(study -> study.getSites().size()).reduce(0, Integer::sum);
    userAppBean.setTotalSitesCount(totalSitesCount);
  }

  private List<UserStudyDetails> getUserStudies(AppEntity app, UserRegAdminEntity adminDetails) {
    List<UserStudyDetails> userStudies = new ArrayList<>();

    for (StudyEntity existingStudy : CollectionUtils.emptyIfNull(app.getStudies())) {
      UserStudyDetails studyResponse = UserMapper.toStudiesResponseBean(existingStudy);
      setSelectedAndStudyPermission(adminDetails, app.getId(), studyResponse);
      List<UserSiteDetails> userSites = new ArrayList<>();
      List<SiteEntity> sites = existingStudy.getSites();
      for (SiteEntity site : CollectionUtils.emptyIfNull(sites)) {
        UserSiteDetails siteResponse = UserMapper.toSitesResponseBean(site);
        setSelectedAndSitePermission(
            site.getId(), adminDetails, app.getId(), siteResponse, studyResponse.getStudyId());
        userSites.add(siteResponse);
      }

      studyResponse.getSites().addAll(userSites);

      int selectedSitesCount = (int) userSites.stream().filter(UserSiteDetails::isSelected).count();
      studyResponse.setSelectedSitesCount(selectedSitesCount);
      studyResponse.setTotalSites(userSites.size());

      userStudies.add(studyResponse);
    }

    return userStudies;
  }

  private void setSelectedAndSitePermission(
      String siteId,
      UserRegAdminEntity admin,
      String appId,
      UserSiteDetails siteResponse,
      String studyId) {
    logger.entry("setSelectedAndSitePermission()");
    Optional<SitePermissionEntity> optSitePermission =
        sitePermissionRepository.findByAdminIdAndAppIdAndStudyIdAndSiteId(
            siteId, admin.getId(), appId, studyId);
    if (optSitePermission.isPresent()) {
      SitePermissionEntity studyPermission = optSitePermission.get();
      Permission permission = Permission.fromValue(studyPermission.getCanEdit());
      siteResponse.setPermission(permission.value());
      if (Permission.NO_PERMISSION != permission) {
        siteResponse.setSelected(true);
      }
    }

    logger.exit(String.format("site permission found=%b", optSitePermission.isPresent()));
  }

  private void setSelectedAndStudyPermission(
      UserRegAdminEntity admin, String appId, UserStudyDetails studyResponse) {
    logger.entry("setSelectedAndStudyPermission()");
    Optional<StudyPermissionEntity> optStudyPermission =
        studyPermissionRepository.findByAdminIdAndAppIdAndStudyId(
            admin.getId(), appId, studyResponse.getStudyId());
    if (optStudyPermission.isPresent()) {
      StudyPermissionEntity studyPermission = optStudyPermission.get();
      Permission permission = Permission.fromValue(studyPermission.getEdit());
      studyResponse.setPermission(permission.value());
      if (Permission.NO_PERMISSION != permission) {
        studyResponse.setSelected(true);
      }
    }

    logger.exit(String.format("study permission found=%b", optStudyPermission.isPresent()));
  }

  private ErrorCode validateUserRequest(String adminUserId) {
    Optional<UserRegAdminEntity> optAdminDetails = userAdminRepository.findById(adminUserId);
    if (!optAdminDetails.isPresent()) {
      return ErrorCode.USER_NOT_FOUND;
    }

    if (!optAdminDetails.get().isSuperAdmin()) {
      return ErrorCode.NOT_SUPER_ADMIN_ACCESS;
    }

    return null;
  }

  @Override
  public GetUsersResponse getUsers(String superAdminUserId, AuditLogEventRequest auditRequest) {
    logger.entry("getUsers()");
    ErrorCode errorCode = validateUserRequest(superAdminUserId);
    if (errorCode != null) {
      logger.error(errorCode.toString());
      return new GetUsersResponse(errorCode);
    }

    List<User> users = new ArrayList<>();
    List<UserRegAdminEntity> adminList = userAdminRepository.findAll();
    adminList
        .stream()
        .map(admin -> users.add(UserMapper.prepareUserInfo(admin)))
        .collect(Collectors.toList());

    participantManagerHelper.logEvent(ParticipantManagerEvent.USER_REGISTRY_VIEWED, auditRequest);

    logger.exit(String.format("total users=%d", adminList.size()));
    return new GetUsersResponse(MessageCode.GET_USERS_SUCCESS, users);
  }
}
