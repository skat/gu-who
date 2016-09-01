/*
 * Copyright 2014 The Guardian
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package lib

import lib.Implicits._
import org.kohsuke.github._
import play.api.Logger

import scala.collection.convert.wrapAsScala._
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent._
import scala.util.{Failure, Success, Try}
import scalax.file.ImplicitConversions._

object OrgSnapshot {
  
  def apply(auditDef: AuditDef): Future[OrgSnapshot] = {
    val org = auditDef.org
    val peopleRepo = org.peopleRepo
    val conn = auditDef.ghCreds.conn()

    val usersF = Future {
      org.listMembers.map { u => conn.getUser(u.getLogin) }.toSet
    } flatMap {
      Future.traverse(_)(u => Future { conn.getUser(u.getLogin) })
    } andThen { case us => Logger.info(s"User count: ${us.map(_.size)}") }

    val sponsoredUserLoginsF = Future {
      PeopleRepo.getSponsoredUserLogins(
        auditDef.workingDir,
        peopleRepo.gitHttpTransportUrl,
        Some(auditDef.ghCreds.git)
      )
    }

    val botUsersF: Future[Set[GHUser]] = Future {
      org.botsTeamOpt.toSeq.flatMap(_.getMembers.toSeq).toSet
    } andThen { case us => Logger.info(s"bots team count: ${us.map(_.size)}") }

    val twoFactorAuthDisabledUsersF = Future {
      org.listMembersWithFilter("2fa_disabled").asList().toSet
    } andThen { case us => Logger.info(s"2fa_disabled count: ${us.map(_.size)}") }  

    val openIssuesF = Future {
      peopleRepo.getIssues(GHIssueState.OPEN).toSet.filter(_.getUser==auditDef.bot)
    } andThen { case is => Logger.info(s"Open issue count: ${is.map(_.size)}") }

    for {
      users <- usersF
      sponsoredUserLogins <- sponsoredUserLoginsF
      twoFactorAuthDisabledUsers <- twoFactorAuthDisabledUsersF.trying
      openIssues <- openIssuesF
      botUsers <- botUsersF
    } yield OrgSnapshot(org, users, botUsers, sponsoredUserLogins, twoFactorAuthDisabledUsers, openIssues)
  }
}

case class OrgSnapshot(
  org: GHOrganization,
  users: Set[GHUser],
  botUsers: Set[GHUser],
  sponsoredUserLogins: Set[String],
  twoFactorAuthDisabledUserLogins: Try[Set[GHUser]],
  openIssues: Set[GHIssue]
) {

  lazy val sponsoredUserLoginsLowerCase = sponsoredUserLogins.map(_.toLowerCase)

  private lazy val evaluatorsByRequirement= AccountRequirements.All.map(ar => ar -> ar.userEvaluatorFor(this)).toMap

  lazy val availableRequirementEvaluators: Iterable[AccountRequirement#UserEvaluator] = evaluatorsByRequirement.collect { case (_, Success(evaluator)) => evaluator }

  lazy val requirementsWithUnavailableEvaluators = evaluatorsByRequirement.collect { case (ar, Failure(t)) => ar -> t }

  lazy val orgUserProblemsByUser = users.map {
    user =>
      val applicableAvailableEvaluators = availableRequirementEvaluators.filter(_.appliesTo(user)).toSet

      user -> OrgUserProblems(
        org,
        user,
        applicableRequirements = applicableAvailableEvaluators.map(_.requirement),
        problems = applicableAvailableEvaluators.filterNot(_.isSatisfiedBy(user)).map(_.requirement)
      )
  }.toMap

  lazy val usersWithProblemsCount = orgUserProblemsByUser.values.count(_.problems.nonEmpty)

  lazy val proportionOfUsersWithProblems = usersWithProblemsCount.toFloat / users.size

  lazy val soManyUsersHaveProblemsThatPerhapsTheGitHubAPIIsBroken = proportionOfUsersWithProblems > 0.9

  lazy val problemUsersExist = usersWithProblemsCount > 0

  lazy val orgUserProblemStats = orgUserProblemsByUser.values.map(_.problems.size).groupBy(identity).mapValues(_.size)

  def updateExistingAssignedIssues() {
    for {
      issue <- openIssues
      user <- issue.assignee
      orgUserProblems <- orgUserProblemsByUser.get(user)
    } orgUserProblems.updateIssue(issue)
  }

  def closeUnassignedIssues() {
    for {
      issue <- openIssues if issue.assignee.isEmpty
    } {
      issue.comment(
        "Closing this issue as it's not assigned to any user, so this bot can not process it. " +
          "Perhaps the user account was deleted?")
      issue.close()
    }
  }

  def createIssuesForNewProblemUsers() {
    val usersWithOpenIssues = openIssues.flatMap(_.assignee)
    for {
      (user, orgUserProblems) <- orgUserProblemsByUser -- usersWithOpenIssues
      if orgUserProblems.problems.nonEmpty
    } orgUserProblems.createIssue()
  }
}
