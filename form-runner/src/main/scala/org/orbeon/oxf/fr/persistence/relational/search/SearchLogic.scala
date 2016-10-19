/**
 * Copyright (C) 2016 Orbeon, Inc.
 *
 * This program is free software; you can redistribute it and/or modify it under the terms of the
 * GNU Lesser General Public License as published by the Free Software Foundation; either version
 * 2.1 of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY;
 * without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 * See the GNU Lesser General Public License for more details.
 *
 * The full text of the license is available at http://www.gnu.org/copyleft/lesser.html
 */
package org.orbeon.oxf.fr.persistence.relational.search

import java.sql.Timestamp

import org.orbeon.oxf.fr.{FormRunner, ParametrizedRole}
import org.orbeon.oxf.fr.permission.PermissionsAuthorization.PermissionsCheck
import org.orbeon.oxf.fr.permission.{Permissions ⇒ _, _}
import org.orbeon.oxf.fr.persistence.relational.RelationalUtils
import org.orbeon.oxf.fr.persistence.relational.RelationalUtils.Logger
import org.orbeon.oxf.fr.persistence.relational.Statement._
import org.orbeon.oxf.fr.persistence.relational.crud.Organization
import org.orbeon.oxf.fr.persistence.relational.search.adt.{Document, _}
import org.orbeon.oxf.fr.persistence.relational.search.part._
import org.orbeon.oxf.util.CoreUtils._
import org.orbeon.oxf.util.SQLUtils._
import org.orbeon.oxf.util.CollectionUtils._
import org.orbeon.scaxon.XML._

trait SearchLogic extends SearchRequest {

  private val SearchOperationsLegacy = List("read", "update", "delete")
  private val SearchOperations       = List(Read, Update, Delete)

  private def computePermissions(request: Request): Permissions = {

    val formPermissionsElOpt            = RelationalUtils.readFormPermissions(request.app, request.form)
    val formPermissions                 = PermissionsXML.parse(formPermissionsElOpt.orNull)
    val user                            = PermissionsAuthorization.currentUserFromSession

    def hasPermissionCond(cond: String): Boolean =
      formPermissionsElOpt.exists(_.child("permission").child(cond).nonEmpty)
    def authorizedIf(check: PermissionsCheck): Boolean = {
      val authorized = PermissionsAuthorization.authorizedOperations(formPermissions, user, check)
      Operations.allowsAny(authorized, SearchOperations)
    }

    Permissions(
      formPermissionsElOpt,
      authorizedBasedOnRole         = {
        val check                = PermissionsAuthorization.CheckWithoutDataUser(optimistic = false)
        val authorizedOperations = PermissionsAuthorization.authorizedOperations(formPermissions, user, check)
        Operations.allowsAny(authorizedOperations, SearchOperations)
      },
      authorizedIfOrganizationMatch = {
        val check  = PermissionsAuthorization.CheckAssumingOrganizationMatch
        val userParameterizedRoles = user.roles.collect{ case role@ParametrizedRole(_, _) ⇒ role }
        val usefulUserParameteriedRoles = userParameterizedRoles.filter(role ⇒ {
          val userWithJustThisRole = user.copy(roles = List(role))
          val authorizedOperations = PermissionsAuthorization.authorizedOperations(formPermissions, userWithJustThisRole, check)
          Operations.allowsAny(authorizedOperations, SearchOperations)
        } )
        usefulUserParameteriedRoles.map(_.organizationName)
      },
      authorizedIfUsername          = hasPermissionCond("owner")       .option(request.username).flatten,
      authorizedIfGroup             = hasPermissionCond("group-member").option(request.group).flatten
    )
  }

  def doSearch(request: Request): (List[Document], Int) =  {

    val permissions = computePermissions(request)
    val hasNoPermissions =
      ! permissions.authorizedBasedOnRole &&
      permissions.authorizedIfUsername.isEmpty &&
      permissions.authorizedIfGroup.isEmpty

    if (hasNoPermissions)
      // There is no chance we can access any data, no need to run any SQL
      (Nil, 0)
    else
      RelationalUtils.withConnection { connection ⇒

        val commonParts = List(
          commonPart         (request),
          draftsPart         (request),
          permissionsPart    (permissions),
          columnFilterPart   (request),
          freeTextFilterPart (request)
        )

        val searchCount = {

          val innerSQL = buildQuery(commonParts)
          val sql =
            s"""SELECT count(*)
               |  FROM (
               |       $innerSQL
               |       ) a
             """.stripMargin

          Logger.logDebug("search total query", sql)
          val rs = executeQuery(connection, sql, commonParts)
          rs.next()
          rs.getInt(1)
        }

        val documentsResultSet = {

          // Build SQL and create statement
          val parts =
            commonParts :+
            mySqlOrderForRowNumPart(request)
          val innerSQL = buildQuery(parts)

          val startOffsetZeroBased = (request.pageNumber - 1) * request.pageSize
          // Use LEFT JOIN instead of regular join, in case the form doesn't have any control marked
          // to be indexed, in which case there won't be anything for it in orbeon_i_control_text.
          val sql =
            s"""    SELECT c.*, t.control, t.pos, t.val
               |      FROM (
               |           $innerSQL
               |           ) c
               | LEFT JOIN orbeon_i_control_text t
               |           ON c.data_id = t.data_id
               |     WHERE row_number
               |           BETWEEN ${startOffsetZeroBased + 1}
               |           AND     ${startOffsetZeroBased + request.pageSize}
               |""".stripMargin

          Logger.logDebug("search items query", sql)
          executeQuery(connection, sql, parts)
        }

        val documents =
          Iterator.iterateWhile(
            cond = documentsResultSet.next(),
            elem = (
                DocumentMetaData(
                  documentId       = documentsResultSet.getString        ("document_id"),
                  draft            = documentsResultSet.getString        ("draft") == "Y",
                  created          = documentsResultSet.getTimestamp     ("created"),
                  lastModifiedTime = documentsResultSet.getTimestamp     ("last_modified_time"),
                  lastModifiedBy   = documentsResultSet.getString        ("last_modified_by"),
                  username         = Option(documentsResultSet.getString ("username")),
                  groupname        = Option(documentsResultSet.getString ("groupname"))
                ),
                DocumentValue(
                  control          = documentsResultSet.getString        ("control"),
                  pos              = documentsResultSet.getInt           ("pos"),
                  value            = documentsResultSet.getString        ("val")
                )
            )
          )
            .toList

            // Group row by common metadata, since the metadata is repeated in the result set
            .groupBy(_._1).mapValues(_.map(_._2)).toList

            // Sort by last modified in descending order, as the call expects the result to be pre-sorted
            .sortBy(_._1.lastModifiedTime)(Ordering[Timestamp].reverse)

            // Compute possible operations for each document
            .map{ case (metadata, values) ⇒
              val operations =
                permissions.formPermissionsElOpt
                .map(FormRunner.allAuthorizedOperations(_, metadata.username, metadata.groupname, None))
                .getOrElse(SearchOperationsLegacy)
              Document(metadata, operations, values)
            }
          (documents, searchCount)
        }
    }

}
