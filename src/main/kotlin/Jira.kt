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
    println("QuickTag: :updateJira: Found JIRA Ticket ID: $jiraTicketId")

    if (!confirmAction("Set JIRA Ticket $jiraTicketId status to 'Verify'")) {
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

fun setStatusToVerify(prUrl: String, jiraTicketId: String) {
    val jiraEmail = System.getenv("JIRA_USERNAME") ?: run {
        println("⚠️ JIRA_USERNAME environment variable not set. Skipping Jira status update.")
        return
    }
    val jiraApiToken = System.getenv("JIRA_API_TOKEN") ?: run {
        println("⚠️ JIRA_API_TOKEN environment variable not set. Skipping Jira status update.")
        return
    }
    val jiraBaseUrl = "https://hotstar.atlassian.net"

    try {
        // Get issue details to check if it's a subtask and get current status
        val issueDetails = getJiraIssue(jiraTicketId, jiraBaseUrl, jiraEmail, jiraApiToken)

        // Transition current issue to "Verify" status
        transitionJiraIssue(jiraTicketId, "Verify", jiraBaseUrl, jiraEmail, jiraApiToken)

        // Check if this is a subtask
        val parentKey = issueDetails.optJSONObject("fields")?.optJSONObject("parent")?.optString("key")
        if (parentKey != null) {
            println("🔍 Ticket $jiraTicketId is a subtask of $parentKey")

            // Get all subtasks of the parent
            val parentDetails = getJiraIssue(parentKey, jiraBaseUrl, jiraEmail, jiraApiToken)
            val subtasks = parentDetails.getJSONObject("fields").optJSONArray("subtasks")

            if (subtasks != null) {
                var allSubtasksDone = true

                for (i in 0 until subtasks.length()) {
                    val subtask = subtasks.getJSONObject(i)
                    val subtaskKey = subtask.getString("key")
                    val subtaskFields = subtask.getJSONObject("fields")
                    val subtaskStatus = subtaskFields.getJSONObject("status").getString("name")

                    println("   Subtask $subtaskKey status: $subtaskStatus")

                    // Consider subtask done if status is "Done", "Verify", "Closed", or similar completion statuses
                    if (!isCompletionStatus(subtaskStatus)) {
                        allSubtasksDone = false
                    }
                }

                if (allSubtasksDone) {
                    println("✅ All subtasks are complete.")

                    if (confirmAction("Update parent $parentKey to Verify status")) {
                        transitionJiraIssue(parentKey, "Verify", jiraBaseUrl, jiraEmail, jiraApiToken)
                        println("✅ Parent $parentKey updated to Verify status.")
                    } else {
                        println("⏭️  Skipped updating parent status")
                    }

                    if (confirmAction("Update Solution/Implementation field for parent $parentKey")) {
                        setSolutionField(prUrl, jiraBaseUrl, parentKey, jiraEmail, jiraApiToken)
                    } else {
                        println("⏭️  Skipped updating solution field for parent")
                    }
                } else {
                    println("⏳ Not all subtasks are complete yet. Parent status will not be updated.")
                }
            }
        } else {
            if (confirmAction("Update Solution/Implementation field for $jiraTicketId")) {
                setSolutionField(prUrl, jiraBaseUrl, jiraTicketId, jiraEmail, jiraApiToken)
            } else {
                println("⏭️  Skipped updating solution field")
            }
        }

    } catch (e: Exception) {
        println("❌ Error setting Jira status to Verify: ${e.message}")
    }
}

private fun setSolutionField(
    prUrl: String,
    jiraBaseUrl: String,
    jiraTicketId: String,
    jiraEmail: String,
    jiraApiToken: String,
) {
    val solutionFieldId = "customfield_13015"
    val client = OkHttpClient()
    val credentials = Credentials.basic(jiraEmail, jiraApiToken)

    // Step 1: Fetch the current issue to get existing solution field content
    val getRequest = Request.Builder()
        .url("$jiraBaseUrl/rest/api/3/issue/$jiraTicketId?fields=$solutionFieldId")
        .get()
        .header("Authorization", credentials)
        .build()

    val getResponse = client.newCall(getRequest).execute()
    if (!getResponse.isSuccessful) {
        println("⚠️ Failed to fetch current field for $jiraTicketId: ${getResponse.code}")
        return
    }

    // Step 2: Parse existing ADF content
    val responseBody = getResponse.body?.string()
    val existingContent = JSONArray()

    try {
        val issueJson = JSONObject(responseBody ?: "{}")
        val existingAdf = issueJson.optJSONObject("fields")
            ?.optJSONObject(solutionFieldId)

        if (existingAdf != null) {
            val existingContentArray = existingAdf.optJSONArray("content")
            if (existingContentArray != null) {
                for (i in 0 until existingContentArray.length()) {
                    existingContent.put(existingContentArray.get(i))
                }
            }
        }
    } catch (e: Exception) {
        println("⚠️ Error parsing existing content: ${e.message}")
    }

    // Step 3: Create new paragraph with PR link
    val newParagraph = JSONObject().apply {
        put("type", "paragraph")
        put("content", JSONArray().apply {
            put(JSONObject().apply {
                put("type", "text")
                put("text", "See ")
            })
            put(JSONObject().apply {
                put("type", "text")
                put("text", prUrl)
                put("marks", JSONArray().apply {
                    put(JSONObject().apply {
                        put("type", "link")
                        put("attrs", JSONObject().apply {
                            put("href", prUrl)
                        })
                    })
                })
            })
            put(JSONObject().apply {
                put("type", "text")
                put("text", "'s description")
            })
        })
    }

    // Step 4: Append new paragraph to existing content
    existingContent.put(newParagraph)

    // Step 5: Build complete ADF document
    val adfContent = JSONObject().apply {
        put("version", 1)
        put("type", "doc")
        put("content", existingContent)
    }

    val solutionUpdateBody = JSONObject().apply {
        put("fields", JSONObject().apply {
            put(solutionFieldId, adfContent)
        })
    }

    // Step 6: Send PUT request to update the field
    val apiUrl = "$jiraBaseUrl/rest/api/3/issue/$jiraTicketId"
    val updateRequest = Request.Builder()
        .url(apiUrl)
        .put(solutionUpdateBody.toString().toRequestBody("application/json".toMediaType()))
        .header("Authorization", credentials)
        .header("Content-Type", "application/json")
        .build()

    /*val curl = "curl -X PUT '$apiUrl' \\\n" +
            "  -H 'Authorization: Basic ${java.util.Base64.getEncoder().encodeToString("$jiraEmail:$jiraApiToken".toByteArray())}' \\\n" +
            "  -H 'Content-Type: application/json' \\\n" +
            "  -d '${solutionUpdateBody.toString()}'"
    println("🔧 Curl for updating 'Solution / Implementation' field:\n$curl")*/

    val updateResponse = client.newCall(updateRequest).execute()
    if (!updateResponse.isSuccessful) {
        println("⚠️ Failed to update 'Solution / Implementation' field for $jiraTicketId: ${updateResponse.code} ${updateResponse.message}")
        println("Response body: ${updateResponse.body?.string()}")
    } else {
        println("✅ Updated 'Solution / Implementation' field for $jiraTicketId")
    }
}

private fun isCompletionStatus(status: String): Boolean {
    val completionStatuses = setOf("done", "verify", "closed", "resolved", "completed")
    return completionStatuses.contains(status.lowercase())
}

private fun getJiraIssue(
    issueKey: String,
    jiraBaseUrl: String,
    jiraEmail: String,
    jiraApiToken: String,
): org.json.JSONObject {
    val apiUrl = "$jiraBaseUrl/rest/api/3/issue/$issueKey"

    val credentials = okhttp3.Credentials.basic(jiraEmail, jiraApiToken)
    val request = okhttp3.Request.Builder()
        .url(apiUrl)
        .header("Authorization", credentials)
        .header("Accept", "application/json")
        .build()

    val client = okhttp3.OkHttpClient()
    val response = client.newCall(request).execute()

    if (!response.isSuccessful) {
        throw Exception("Failed to fetch Jira issue $issueKey: ${response.code} ${response.message}")
    }

    return org.json.JSONObject(response.body.string())
}

private fun transitionJiraIssue(
    issueKey: String,
    targetStatus: String,
    jiraBaseUrl: String,
    jiraEmail: String,
    jiraApiToken: String,
) {
    // First, get available transitions for the issue
    val transitionsUrl = "$jiraBaseUrl/rest/api/3/issue/$issueKey/transitions"
    val credentials = okhttp3.Credentials.basic(jiraEmail, jiraApiToken)

    val getTransitionsRequest = okhttp3.Request.Builder()
        .url(transitionsUrl)
        .header("Authorization", credentials)
        .header("Accept", "application/json")
        .build()

    val client = okhttp3.OkHttpClient()
    val transitionsResponse = client.newCall(getTransitionsRequest).execute()

    if (!transitionsResponse.isSuccessful) {
        throw Exception("Failed to fetch transitions for $issueKey: ${transitionsResponse.code}")
    }

    val transitionsJson = org.json.JSONObject(transitionsResponse.body.string())
    val transitions = transitionsJson.getJSONArray("transitions")

    // Find the transition ID for the target status
    var transitionId: String? = null
    for (i in 0 until transitions.length()) {
        val transition = transitions.getJSONObject(i)
        val toStatus = transition.getJSONObject("to").getString("name")
        if (toStatus.equals(targetStatus, ignoreCase = true)) {
            transitionId = transition.getString("id")
            break
        }
    }

    if (transitionId == null) {
        println("⚠️ No transition to '$targetStatus' found for issue $issueKey. Available transitions:")
        for (i in 0 until transitions.length()) {
            val transition = transitions.getJSONObject(i)
            println("   - ${transition.getJSONObject("to").getString("name")}")
        }
        return
    }

    // Perform the transition
    val transitionBody = org.json.JSONObject().apply {
        put("transition", org.json.JSONObject().put("id", transitionId))
    }

    val transitionRequest = okhttp3.Request.Builder()
        .url(transitionsUrl)
        .post(transitionBody.toString().toRequestBody("application/json".toMediaType()))
        .header("Authorization", credentials)
        .header("Content-Type", "application/json")
        .build()

    val transitionResponse = client.newCall(transitionRequest).execute()

    if (!transitionResponse.isSuccessful) {
        val errorBody = transitionResponse.body.string()
        throw Exception("Failed to transition $issueKey to $targetStatus: ${transitionResponse.code}. Details: $errorBody")
    }

    println("✅ Successfully transitioned $issueKey to $targetStatus")
}

fun extractJiraTicketId(prUrl: String): String? {
    val githubApiKey = System.getenv("GITHUB_ACCESS_TOKEN") ?: return null

    // Parse PR URL to extract owner, repo, and PR number
    val urlParts = prUrl.split("/")
    if (urlParts.size < 7 || !prUrl.contains("github.com")) {
        return null
    }

    val owner = urlParts[3]
    val repo = urlParts[4]
    val prNumber = urlParts[6]

    // Fetch PR details from GitHub API
    val apiUrl = "https://api.github.com/repos/$owner/$repo/pulls/$prNumber"
    val request = okhttp3.Request.Builder()
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
        val json = org.json.JSONObject(responseBody)

        // Extract branch name from the "head" ref
        val branchName = json.getJSONObject("head").getString("ref")

        // Extract Jira ticket ID using regex pattern (e.g., AF-14934)
        val jiraTicketPattern = Regex("[A-Z]+-\\d+")
        jiraTicketPattern.find(branchName)?.value
    } catch (e: Exception) {
        println("❌ Error extracting Jira ticket ID: ${e.message}")
        null
    }
}