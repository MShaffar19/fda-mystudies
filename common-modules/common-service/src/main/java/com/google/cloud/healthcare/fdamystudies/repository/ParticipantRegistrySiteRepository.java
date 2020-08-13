/*
 * Copyright 2020 Google LLC
 *
 * Use of this source code is governed by an MIT-style
 * license that can be found in the LICENSE file or at
 * https://opensource.org/licenses/MIT.
 */
package com.google.cloud.healthcare.fdamystudies.repository;

import com.google.cloud.healthcare.fdamystudies.model.ParticipantRegistrySiteEntity;
import java.util.List;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

@ConditionalOnProperty(
    value = "participant.manager.repository.enabled",
    havingValue = "true",
    matchIfMissing = false)
@Repository
public interface ParticipantRegistrySiteRepository
    extends JpaRepository<ParticipantRegistrySiteEntity, String> {

  @Query("SELECT pr FROM ParticipantRegistrySiteEntity pr WHERE pr.site.id in (:siteIds)")
  public List<ParticipantRegistrySiteEntity> findParticipantRegistryBySiteIds(
      @Param("siteIds") List<String> siteIds);
}
