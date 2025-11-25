import okhttp3.Credentials
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

fun updateJira(prUrl: String) {
    // Current username
    val userHome = System.getProperty("user.home")
    if (userHome != "/Users/theapache64") {
        return
    }

    // Get JIRA ticket ID from PR URL
    val jiraTicketId = extractJiraTicketId(prUrl) ?: error("JIRA ticket ID not found in PR URL")
    println("🔍 Found JIRA Ticket ID: $jiraTicketId")

    if (!confirmAction("Set JIRA Ticket ${jConfig?.baseUrl}/browse/$jiraTicketId status to 'Verify'")) {
        println("⏭️  Skipped setting status to Verify")
        return
    }

    setStatusToVerify(prUrl, jiraTicketId)
    println("QuickTag: :updateJira: Set JIRA Ticket $jiraTicketId status to 'Verify'")
}

/**
 * Prompts user for confirmation before performing an action
 * @param actionDescription Description of the action to be performed
 * @return true if user confirms (presses ENTER), false if user skips (types 's' or 'skip')
 */
private fun confirmAction(actionDescription: String): Boolean {
    println("\n━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
    println("⚡ About to: $actionDescription")
    println("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
    println("Press ENTER to continue, or type 's' to skip: ")

    val input = readLine()?.trim()?.lowercase() ?: ""
    return input.isEmpty() || (input != "s" && input != "skip")
}

/**
 * Data class to hold Jira credentials and configuration
 */
data class JiraConfig(
    val email: String,
    val apiToken: String,
    val baseUrl: String
)

/**
 * Gets Jira configuration from environment variables
 * @return JiraConfig if credentials are available, null otherwise
 */
private fun getJiraConfig(): JiraConfig? {
    val jiraEmail = System.getenv("JIRA_USERNAME") ?: run {
        println("⚠️ JIRA_USERNAME environment variable not set. Skipping Jira status update.")
        return null
    }
    val jiraApiToken = System.getenv("JIRA_API_TOKEN") ?: run {
        println("⚠️ JIRA_API_TOKEN environment variable not set. Skipping Jira status update.")
        return null
    }
    val jiraBaseUrl = System.getenv("JIRA_BASE_URL") ?: run {
        println("⚠️ JIRA_BASE_URL environment variable not set. Skipping Jira status update.")
        return null
    }
    return JiraConfig(jiraEmail, jiraApiToken, jiraBaseUrl)
}

val jConfig = getJiraConfig()

/**
 * Creates basic auth credentials for Jira API
 */
private fun JiraConfig.getCredentials(): String = Credentials.basic(email, apiToken)

/**
 * Builds a Jira API request with standard headers
 */
private fun buildJiraRequest(url: String, credentials: String): Request.Builder {
    return Request.Builder()
        .url(url)
        .header("Authorization", credentials)
        .header("Accept", "application/json")
}

fun setStatusToVerify(prUrl: String, jiraTicketId: String) {
    val config = getJiraConfig() ?: return

    try {
        // Get issue details to check if it's a subtask and get current status
        val issueDetails = getJiraIssue(jiraTicketId, config)

        // Transition current issue to "Verify" status
        transitionJiraIssue(jiraTicketId, "Verify", config)

        // Check if this is a subtask
        val parentKey = issueDetails.optJSONObject("fields")?.optJSONObject("parent")?.optString("key")
        val isStory = issueDetails.optJSONObject("fields")
            ?.optJSONObject("issuetype")
            ?.optString("name")
            ?.equals("Story", ignoreCase = true) ?: false
        if (parentKey != null && !isStory) {
            handleSubtaskCompletion(prUrl, jiraTicketId, parentKey, config)
        } else {
            handleRegularTicket(prUrl, jiraTicketId, config)
        }

    } catch (e: Exception) {
        println("❌ Error setting Jira status to Verify: ${e.message}")
    }
}

/**
 * Handles the case when all subtasks are complete
 */
private fun handleSubtaskCompletion(
    prUrl: String,
    jiraTicketId: String,
    parentKey: String,
    config: JiraConfig
) {
    println("🔍 Ticket $jiraTicketId is a subtask of $parentKey")

    // Get all subtasks of the parent
    val parentDetails = getJiraIssue(parentKey, config)
    val subtasks = parentDetails.getJSONObject("fields").optJSONArray("subtasks")

    if (subtasks != null && areAllSubtasksComplete(subtasks)) {
        println("✅ All subtasks are complete.")

        if (confirmAction("Update parent ${jConfig?.baseUrl}/browse/$parentKey to Verify status")) {
            transitionJiraIssue(parentKey, "Verify", config)
            println("✅ Parent $parentKey updated to Verify status.")
        } else {
            println("⏭️  Skipped updating parent status")
        }

        updateSolutionFieldIfConfirmed(prUrl, parentKey, config, "parent")
    } else {
        println("⏳ Not all subtasks are complete yet. Parent status will not be updated.")
    }
}

/**
 * Handles updating solution field for regular (non-subtask) tickets
 */
private fun handleRegularTicket(prUrl: String, jiraTicketId: String, config: JiraConfig) {
    updateSolutionFieldIfConfirmed(prUrl, jiraTicketId, config, "ticket")
}

/**
 * Prompts for confirmation and updates solution field if confirmed
 */
private fun updateSolutionFieldIfConfirmed(
    prUrl: String,
    ticketId: String,
    config: JiraConfig,
    ticketType: String
) {
    if (confirmAction("Update Solution/Implementation field for $ticketType ${jConfig?.baseUrl}/browse/$ticketId")) {
        setSolutionField(prUrl, ticketId, config)
    } else {
        println("⏭️  Skipped updating solution field for $ticketType")
    }
}

/**
 * Checks if all subtasks are in a completion status
 */
private fun areAllSubtasksComplete(subtasks: JSONArray): Boolean {
    var allComplete = true

    for (i in 0 until subtasks.length()) {
        val subtask = subtasks.getJSONObject(i)
        val subtaskKey = subtask.getString("key")
        val subtaskStatus = subtask.getJSONObject("fields")
            .getJSONObject("status")
            .getString("name")

        println("   Subtask $subtaskKey status: $subtaskStatus")

        if (!isCompletionStatus(subtaskStatus)) {
            allComplete = false
        }
    }

    return allComplete
}

private fun setSolutionField(
    prUrl: String,
    jiraTicketId: String,
    config: JiraConfig
) {
    val solutionFieldId = "customfield_13015"
    val client = OkHttpClient()
    val credentials = config.getCredentials()

    try {
        // Fetch current field content
        val existingContent = fetchExistingSolutionContent(jiraTicketId, solutionFieldId, config, client, credentials)

        // Create and append new paragraph
        val newParagraph = createPrLinkParagraph(prUrl)
        existingContent.put(newParagraph)

        // Build and send update
        val adfContent = buildAdfDocument(existingContent)
        updateSolutionFieldOnJira(jiraTicketId, solutionFieldId, adfContent, config, client, credentials)

    } catch (e: Exception) {
        println("⚠️ Error updating solution field: ${e.message}")
    }
}

/**
 * Fetches existing solution field content from Jira
 */
private fun fetchExistingSolutionContent(
    jiraTicketId: String,
    solutionFieldId: String,
    config: JiraConfig,
    client: OkHttpClient,
    credentials: String
): JSONArray {
    val getRequest = Request.Builder()
        .url("${config.baseUrl}/rest/api/3/issue/$jiraTicketId?fields=$solutionFieldId")
        .get()
        .header("Authorization", credentials)
        .build()

    val getResponse = client.newCall(getRequest).execute()
    if (!getResponse.isSuccessful) {
        println("⚠️ Failed to fetch current field for $jiraTicketId: ${getResponse.code}")
        return JSONArray()
    }

    val existingContent = JSONArray()
    try {
        val responseBody = getResponse.body?.string()
        val issueJson = JSONObject(responseBody ?: "{}")
        val existingAdf = issueJson.optJSONObject("fields")
            ?.optJSONObject(solutionFieldId)

        existingAdf?.optJSONArray("content")?.let { contentArray ->
            for (i in 0 until contentArray.length()) {
                existingContent.put(contentArray.get(i))
            }
        }
    } catch (e: Exception) {
        println("⚠️ Error parsing existing content: ${e.message}")
    }

    return existingContent
}

/**
 * Creates a paragraph with PR link in Atlassian Document Format (ADF)
 */
private fun createPrLinkParagraph(prUrl: String): JSONObject {
    return JSONObject().apply {
        put("type", "paragraph")
        put("content", JSONArray().apply {
            put(createTextNode("See "))
            put(createLinkNode(prUrl, prUrl))
            put(createTextNode("'s description"))
        })
    }
}

/**
 * Creates a text node for ADF
 */
private fun createTextNode(text: String): JSONObject {
    return JSONObject().apply {
        put("type", "text")
        put("text", text)
    }
}

/**
 * Creates a link node for ADF
 */
private fun createLinkNode(text: String, href: String): JSONObject {
    return JSONObject().apply {
        put("type", "text")
        put("text", text)
        put("marks", JSONArray().apply {
            put(JSONObject().apply {
                put("type", "link")
                put("attrs", JSONObject().apply {
                    put("href", href)
                })
            })
        })
    }
}

/**
 * Builds complete ADF document structure
 */
private fun buildAdfDocument(content: JSONArray): JSONObject {
    return JSONObject().apply {
        put("version", 1)
        put("type", "doc")
        put("content", content)
    }
}

/**
 * Updates the solution field on Jira with the given ADF content
 */
private fun updateSolutionFieldOnJira(
    jiraTicketId: String,
    solutionFieldId: String,
    adfContent: JSONObject,
    config: JiraConfig,
    client: OkHttpClient,
    credentials: String
) {
    val solutionUpdateBody = JSONObject().apply {
        put("fields", JSONObject().apply {
            put(solutionFieldId, adfContent)
        })
    }

    val apiUrl = "${config.baseUrl}/rest/api/3/issue/$jiraTicketId"
    val updateRequest = Request.Builder()
        .url(apiUrl)
        .put(solutionUpdateBody.toString().toRequestBody("application/json".toMediaType()))
        .header("Authorization", credentials)
        .header("Content-Type", "application/json")
        .build()

    // printCurlCommand(apiUrl, config, solutionUpdateBody)

    val updateResponse = client.newCall(updateRequest).execute()
    if (!updateResponse.isSuccessful) {
        println("⚠️ Failed to update 'Solution / Implementation' field for $jiraTicketId: ${updateResponse.code} ${updateResponse.message}")
        println("Response body: ${updateResponse.body?.string()}")
    } else {
        println("✅ Updated 'Solution / Implementation' field for $jiraTicketId")
    }
}

/**
 * Prints curl command for debugging purposes
 */
private fun printCurlCommand(apiUrl: String, config: JiraConfig, body: JSONObject) {
    val authString = "${config.email}:${config.apiToken}"
    val encodedAuth = java.util.Base64.getEncoder().encodeToString(authString.toByteArray())

    val curl = """
        curl -X PUT '$apiUrl' \
          -H 'Authorization: Basic $encodedAuth' \
          -H 'Content-Type: application/json' \
          -d '${body.toString()}'
    """.trimIndent()

    println("🔧 Curl for updating 'Solution / Implementation' field:\n$curl")
}

private fun isCompletionStatus(status: String): Boolean {
    val completionStatuses = setOf("done", "verify", "closed", "resolved", "completed")
    return completionStatuses.contains(status.lowercase())
}

private fun getJiraIssue(
    issueKey: String,
    config: JiraConfig
): JSONObject {
    val apiUrl = "${config.baseUrl}/rest/api/3/issue/$issueKey"
    val credentials = config.getCredentials()

    val request = buildJiraRequest(apiUrl, credentials).build()
    val client = OkHttpClient()
    val response = client.newCall(request).execute()

    if (!response.isSuccessful) {
        throw Exception("Failed to fetch Jira issue $issueKey: ${response.code} ${response.message}")
    }

    return JSONObject(response.body.string())
}

private fun transitionJiraIssue(
    issueKey: String,
    targetStatus: String,
    config: JiraConfig
) {
    val transitionsUrl = "${config.baseUrl}/rest/api/3/issue/$issueKey/transitions"
    val credentials = config.getCredentials()
    val client = OkHttpClient()

    // Get available transitions
    val getTransitionsRequest = buildJiraRequest(transitionsUrl, credentials).build()
    val transitionsResponse = client.newCall(getTransitionsRequest).execute()

    if (!transitionsResponse.isSuccessful) {
        throw Exception("Failed to fetch transitions for $issueKey: ${transitionsResponse.code}")
    }

    val transitionsJson = JSONObject(transitionsResponse.body.string())
    val transitions = transitionsJson.getJSONArray("transitions")

    // Find the transition ID for the target status
    val transitionId = findTransitionId(transitions, targetStatus, issueKey) ?: return

    // Perform the transition
    performTransition(issueKey, transitionId, transitionsUrl, credentials, client)
}

/**
 * Finds the transition ID for a target status
 */
private fun findTransitionId(
    transitions: JSONArray,
    targetStatus: String,
    issueKey: String
): String? {
    for (i in 0 until transitions.length()) {
        val transition = transitions.getJSONObject(i)
        val toStatus = transition.getJSONObject("to").getString("name")
        if (toStatus.equals(targetStatus, ignoreCase = true)) {
            return transition.getString("id")
        }
    }

    println("⚠️ No transition to '$targetStatus' found for issue $issueKey. Available transitions:")
    for (i in 0 until transitions.length()) {
        val transition = transitions.getJSONObject(i)
        println("   - ${transition.getJSONObject("to").getString("name")}")
    }
    return null
}

/**
 * Performs a Jira transition
 */
private fun performTransition(
    issueKey: String,
    transitionId: String,
    transitionsUrl: String,
    credentials: String,
    client: OkHttpClient
) {
    val transitionBody = JSONObject().apply {
        put("transition", JSONObject().put("id", transitionId))
    }

    val transitionRequest = Request.Builder()
        .url(transitionsUrl)
        .post(transitionBody.toString().toRequestBody("application/json".toMediaType()))
        .header("Authorization", credentials)
        .header("Content-Type", "application/json")
        .build()

    val transitionResponse = client.newCall(transitionRequest).execute()

    if (!transitionResponse.isSuccessful) {
        val errorBody = transitionResponse.body.string()
        throw Exception("Failed to transition $issueKey: ${transitionResponse.code}. Details: $errorBody")
    }

    println("✅ Successfully transitioned $issueKey")
}

fun extractJiraTicketId(prUrl: String): String? {
    val githubApiKey = System.getenv("GITHUB_ACCESS_TOKEN") ?: return null

    val prDetails = parsePrUrl(prUrl) ?: return null
    val branchName = fetchBranchNameFromGitHub(prDetails, githubApiKey) ?: return null

    return extractTicketIdFromBranch(branchName)
}

/**
 * Data class to hold PR details
 */
private data class PrDetails(val owner: String, val repo: String, val prNumber: String)

/**
 * Parses GitHub PR URL to extract owner, repo, and PR number
 */
private fun parsePrUrl(prUrl: String): PrDetails? {
    val urlParts = prUrl.split("/")
    if (urlParts.size < 7 || !prUrl.contains("github.com")) {
        return null
    }

    return PrDetails(
        owner = urlParts[3],
        repo = urlParts[4],
        prNumber = urlParts[6]
    )
}

/**
 * Fetches branch name from GitHub API
 */
private fun fetchBranchNameFromGitHub(prDetails: PrDetails, githubApiKey: String): String? {
    val apiUrl = "https://api.github.com/repos/${prDetails.owner}/${prDetails.repo}/pulls/${prDetails.prNumber}"

    val request = Request.Builder()
        .url(apiUrl)
        .header("Authorization", "Bearer $githubApiKey")
        .header("Accept", "application/vnd.github.v3+json")
        .header("User-Agent", "PR-Filler-Android")
        .build()

    return try {
        val client = OkHttpClient()
        val response = client.newCall(request).execute()

        if (!response.isSuccessful) {
            return null
        }

        val responseBody = response.body.string()
        val json = JSONObject(responseBody)
        json.getJSONObject("head").getString("ref")
    } catch (e: Exception) {
        println("❌ Error fetching branch name: ${e.message}")
        null
    }
}

/**
 * Extracts Jira ticket ID from branch name using regex
 */
private fun extractTicketIdFromBranch(branchName: String): String? {
    val jiraTicketPattern = Regex("[A-Z]+-\\d+")
    return jiraTicketPattern.find(branchName)?.value
}