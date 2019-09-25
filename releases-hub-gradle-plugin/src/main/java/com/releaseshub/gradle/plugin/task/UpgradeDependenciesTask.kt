package com.releaseshub.gradle.plugin.task

import com.jdroid.github.RepositoryId
import com.jdroid.github.client.GitHubClient
import com.jdroid.github.service.IssueService
import com.jdroid.github.service.PullRequestService
import com.releaseshub.gradle.plugin.artifacts.ArtifactUpgrade
import com.releaseshub.gradle.plugin.artifacts.ArtifactUpgradeStatus
import com.releaseshub.gradle.plugin.common.AbstractTask
import java.io.File
import java.io.IOException

open class UpgradeDependenciesTask : AbstractTask() {

    var baseBranch: String? = null
    var headBranchPrefix: String? = null
    var pullRequestEnabled: Boolean = false
    var pullRequestsMax: Int? = null
    var gitHubUserName: String? = null
    var gitHubUserEmail: String? = null
    var gitHubRepositoryOwner: String? = null
    var gitHubRepositoryName: String? = null
    var gitHubWriteToken: String? = null

    init {
        description = "Upgrade dependencies"
    }

    override fun onExecute() {

        getExtension().validateServerName()
        getExtension().validateUserToken()
        getExtension().validateDependenciesClassNames()

        if (pullRequestEnabled) {
            getExtension().validateBaseBranch()
            getExtension().validateHeadBranchPrefix()
            getExtension().validateGitHubRepositoryOwner()
            getExtension().validateGitHubRepositoryName()
            getExtension().validateGitHubWriteToken()
        }

        val artifacts = mutableSetOf<ArtifactUpgrade>()
        val filesMap = mutableMapOf<String, List<String>>()

        dependenciesClassNames!!.forEach {
            val lines = project.rootProject.file(dependenciesBasePath + it).readLines()
            filesMap[dependenciesBasePath + it] = lines

            lines.forEach { line ->
                val artifact = DependenciesParser.extractArtifact(line)
                if (artifact != null && artifact.match(includes, excludes)) {
                    artifacts.add(artifact)
                }
            }
        }

        val artifactsToUpgrade = createArtifactsService().getArtifactsUpgrades(artifacts.toList(), getRepositories()).filter { it.artifactUpgradeStatus == ArtifactUpgradeStatus.PENDING_UPGRADE }

        if (artifactsToUpgrade.isNotEmpty()) {

            var groupsToUpgrade = artifactsToUpgrade.groupBy { it.groupId }.entries.toList()

            if (pullRequestEnabled) {
                configureGit()
                val totalSize = groupsToUpgrade.size
                groupsToUpgrade = groupsToUpgrade.take(pullRequestsMax!!)
                log("Creating ${groupsToUpgrade.size} of $totalSize possible pull requests. Increment the \"pullRequestsMax\" property if you want more pull requests created by task execution.")
                log("")
            }

            log("Dependencies upgraded:")

            groupsToUpgrade.forEach { (groupId, artifactsToUpgradeByGroup) ->

                val headBranch = headBranchPrefix + groupId!!.replace(".", "_", true)

                if (pullRequestEnabled) {
                    prepareGitBranch(headBranch)
                }

                val upgradeResults = upgradeDependencies(filesMap, artifactsToUpgradeByGroup)

                if (pullRequestEnabled) {
                    createPullRequest(upgradeResults, headBranch, groupId)
                }
            }
        } else {
            log("No dependencies upgraded")
        }
    }

    private fun configureGit() {
        gitHubUserName?.let {
            gitHelper.configUserName(it)
        }
        gitHubUserEmail?.let {
            gitHelper.configUserEmail(it)
        }
    }

    private fun prepareGitBranch(headBranch: String) {
        gitHelper.checkout(baseBranch!!)
        gitHelper.pull()

        // Local headBranch cleanup
        commandExecutor.execute("git branch -D $headBranch", project.rootProject.projectDir, true, true)
        gitHelper.prune()
        val execResult = commandExecutor.execute("git checkout $headBranch", project.rootProject.projectDir, true, true)
        if (!execResult.isSuccessful()) {
            gitHelper.createBranch(headBranch)
        }

        // Try to merge from baseBranch to headBranch
        // TODO If there is a conflict, it will fail. Add an error message here telling that the dev need to merge and resolve the conflicts
        gitHelper.merge(baseBranch!!)
    }

    private fun upgradeDependencies(filesMap: MutableMap<String, List<String>>, artifactsToUpgrade: List<ArtifactUpgrade>): List<UpgradeResult> {
        val upgradeResults = mutableListOf<UpgradeResult>()
        val localFilesMap: MutableMap<String, List<String>> = filesMap.toMutableMap()
        artifactsToUpgrade.forEach { artifactToUpgrade ->
            var upgradedUpgradeResult: UpgradeResult? = null
            localFilesMap.entries.forEach { entry ->
                val updatedLines = mutableListOf<String>()
                File(entry.key).bufferedWriter().use { out ->
                    entry.value.forEach { line ->
                        val upgradeResult = DependenciesParser.upgradeDependency(line, artifactToUpgrade)
                        if (upgradeResult.upgraded) {
                            upgradeResults.add(upgradeResult)
                            log(" - ${upgradeResult.artifactUpgrade} ${upgradeResult.artifactUpgrade?.fromVersion} -> ${upgradeResult.artifactUpgrade?.toVersion}")
                            upgradedUpgradeResult = upgradeResult
                        }
                        out.write(upgradeResult.line + "\n")
                        updatedLines.add(upgradeResult.line)
                    }
                }
                localFilesMap[entry.key] = updatedLines
            }
            if (pullRequestEnabled && upgradedUpgradeResult != null) {
                commit(upgradedUpgradeResult!!)
            }
            log("")
        }
        return upgradeResults
    }

    private fun commit(upgradeResult: UpgradeResult) {
        gitHelper.addAll()
        gitHelper.commit("Upgraded ${upgradeResult.artifactUpgrade} from ${upgradeResult.artifactUpgrade!!.fromVersion} to ${upgradeResult.artifactUpgrade.toVersion}")
    }

    private fun createPullRequest(upgradeResults: List<UpgradeResult>, headBranch: String, groupId: String) {
        gitHelper.push(headBranch)
        log("The changes were pushed to $headBranch branch.")

        val client = GitHubClient()
        client.setSerializeNulls(false)
        client.setOAuth2Token(gitHubWriteToken)

        val repositoryIdProvider = RepositoryId.create(gitHubRepositoryOwner, gitHubRepositoryName)
        val pullRequestService = PullRequestService(client)

        try {
            var pullRequest = pullRequestService.getPullRequest(repositoryIdProvider, IssueService.STATE_OPEN, "$gitHubRepositoryOwner:$headBranch", baseBranch)
            if (pullRequest == null) {
                val pullRequestBody = PullRequestGenerator.createBody(upgradeResults)
                pullRequest = pullRequestService.createPullRequest(repositoryIdProvider, "Upgraded dependencies for groupId $groupId", pullRequestBody, headBranch, baseBranch)
                log("The pull request #" + pullRequest!!.number + " was successfully created.")
            } else {
                val pullRequestComment = PullRequestGenerator.createComment(upgradeResults)
                val issueService = IssueService(client)
                issueService.createComment(repositoryIdProvider, pullRequest.number, pullRequestComment)
                log("The pull request #" + pullRequest.number + " already exists, adding a comment")
            }
        } catch (e: IOException) {
            throw RuntimeException(e)
        }
    }
}
