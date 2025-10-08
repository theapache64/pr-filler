import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Data class to hold GitHub PR URL components
 */
data class GitHubPrUrlComponents(
    val owner: String,
    val repo: String,
    val prNumber: String
)

/**
 * PR Filler: A tool to help fill out pull requests using OpenAI's API and GitHub's API.
 */
fun main(args: Array<String>) {
    // [latest version - i promise!]
    println("🙏🏼 Welcome to PR Filler! (v1.0.6)")

    // Parse command line args
    val parsedArgs = parseArgs(args)
    val prUrl = parsedArgs["url"] ?: run {
        // Read PR URL from keyboard
        println("🔗 Please enter the URL of the pull request:")
        readLine() ?: run {
            println("❌ No URL provided. Exiting.")
            return
        }
    }

    // Get AI model from args or use default
    val aiModel = parsedArgs["model"] ?: "gpt-4.1"

    // Validate the URL: eg: https://github.com/mycompany/mycompany-android-mobile/pull/9886
    if (!prUrl.matches(Regex("https://github\\.com/[^/]+/[^/]+/pull/[0-9]+"))) {
        println("⚠️ Invalid URL format. Please provide a valid GitHub pull request URL.")
        return
    }

    val githubApiKey = System.getenv("GITHUB_ACCESS_TOKEN") ?: run {
        println("🔑 GITHUB_ACCESS_TOKEN environment variable not set. Exiting.")
        return
    }

    val openAiApiKey = System.getenv("OPEN_AI_API_KEY") ?: run {
        println("🔑 OPEN_AI_API_KEY environment variable not set. Exiting.")
        return
    }

    // Fetch the diff content using githubApiKey
    val diffContent = try {
        fetchDiffContent(prUrl, githubApiKey)
    } catch (e: Exception) {
        println("❌ Failed to fetch diff content: ${e.message}")
        return
    }
    println("✅ Fetched diff content successfully.")

    // Get PR body
    val prBody = getPrBody(prUrl, githubApiKey)

    // Waiting message for openAI
    println("🤖 Generating new PR body using $aiModel. This may take a moment...")

    // Send prBody (template) and diffContent to openAi API
    val filledPrBody = sendToOpenAiApi(prBody, diffContent, openAiApiKey, aiModel)

    // Update the PR body on GitHub
    updatePrBody(prUrl, filledPrBody, githubApiKey)

    // Print PR URL
    println("🔗 PR URL: $prUrl")
}

fun updatePrBody(prUrl: String, filledPrBody: String, githubApiKey: String) {
    val (owner, repo, prNumber) = parseGitHubPrUrl(prUrl)
    val apiUrl = "https://api.github.com/repos/$owner/$repo/pulls/$prNumber"

    try {
        val json = JSONObject().put("body", filledPrBody)
        val requestBody = json.toString().toRequestBody("application/json".toMediaType())

        val request = Request.Builder()
            .url(apiUrl)
            .patch(requestBody)
            .header("Authorization", "Bearer $githubApiKey")
            .header("Content-Type", "application/json")
            .header("User-Agent", "PR-Filler-Android")
            .build()

        val response = okHttpClient.newCall(request).execute()

        if (!response.isSuccessful) {
            val errorBody = response.body.string()
            throw Exception("Failed to update PR body: ${response.code} - ${response.message}. Details: $errorBody")
        }

        println("✅ Successfully updated PR body on GitHub")
    } catch (e: Exception) {
        println("❌ Error updating PR body: ${e.message}")
        throw e
    }
}

fun sendToOpenAiApi(prBody: String, diffContent: String, openAiApiKey: String, model: String = "gpt-4.1"): String {
    try {
        val messages = JSONArray().apply {
            put(JSONObject().apply {
                put("role", "system")
                put(
                    "content",
                    "You are an assistant that writes precise and concise pull request descriptions using code diffs and optional given template. You always follow the template structure if provided. If no template is provided, create a concise PR description based on the code diff. Do not add any sections that are not present in the template. Always use markdown formatting where applicable. Remember be concise and to the point."
                )
            })
            put(JSONObject().apply {
                put("role", "user")
                put(
                    "content",
                    "Here is the pull request template:\n$prBody\n\nAnd here is the code diff:\n$diffContent\n\n"
                )
            })
        }

        val jsonBody = JSONObject().apply {
            put("model", model)
            put("messages", messages)
            put("temperature", 0.7)
        }

        val request = Request.Builder()
            .url("https://api.openai.com/v1/chat/completions")
            .post(jsonBody.toString().toRequestBody("application/json".toMediaType()))
            .header("Authorization", "Bearer $openAiApiKey")
            .header("User-Agent", "PR-Filler-Android")
            .build()

        val response = okHttpClient.newCall(request).execute()

        if (!response.isSuccessful) {
            throw Exception("OpenAI API request failed with code: ${response.code} - ${response.message}")
        }

        val responseBody = JSONObject(response.body.string())
        val choices = responseBody.getJSONArray("choices")
        if (choices.length() > 0) {
            val content = choices.getJSONObject(0).getJSONObject("message").getString("content")
            return content.trim()
        } else {
            throw Exception("No response content received from OpenAI API.")
        }
    } catch (e: Exception) {
        println("❌ Error in sendToOpenAiApi: ${e.message}")
        throw e
    }
}

private val okHttpClient = OkHttpClient.Builder()
    .apply {
        // Set timeout
        connectTimeout(60, TimeUnit.SECONDS)
        readTimeout(60, TimeUnit.SECONDS)
        writeTimeout(60, TimeUnit.SECONDS)
    }
    .build()

/**
 * Parses a GitHub pull request URL and extracts the owner, repo, and PR number
 */
private fun parseGitHubPrUrl(prUrl: String): GitHubPrUrlComponents {
    val urlParts = prUrl.split("/")
    if (urlParts.size < 7 || !prUrl.contains("github.com")) {
        throw IllegalArgumentException("Invalid GitHub PR URL format")
    }

    return GitHubPrUrlComponents(
        owner = urlParts[3],
        repo = urlParts[4],
        prNumber = urlParts[6]
    )
}

private fun fetchDiffContent(prUrl: String, githubApiKey: String): String {
    // Extract owner, repo, and PR number from the PR URL
    // Example URL: https://github.com/owner/repo/pull/123
    val (owner, repo, prNumber) = parseGitHubPrUrl(prUrl)

    // GitHub API endpoint for PR diff
    val apiUrl = "https://api.github.com/repos/$owner/$repo/pulls/$prNumber"

    val request = Request.Builder()
        .url(apiUrl)
        .header("Authorization", "Bearer $githubApiKey")
        .header("Accept", "application/vnd.github.diff") // Request diff format
        .header("User-Agent", "Android-App") // GitHub API requires User-Agent
        .build()

    return try {
        val response = okHttpClient.newCall(request).execute()

        if (!response.isSuccessful) {
            throw Exception("Failed to fetch PR diff: ${response.code} ${response.message}")
        }

        response.body.string() ?: throw Exception("Empty response body")
    } catch (e: Exception) {
        throw Exception("Error fetching PR diff: ${e.message}", e)
    }
}

private fun getPrBody(prUrl: String, githubApiKey: String): String {
    val (owner, repo, prNumber) = parseGitHubPrUrl(prUrl)

    val prBody = try {
        val request = Request.Builder()
            .url("https://api.github.com/repos/$owner/$repo/pulls/$prNumber")
            .header("Authorization", "Bearer $githubApiKey")
            .header("Accept", "application/vnd.github.v3+json")
            .build()

        val response = okHttpClient.newCall(request).execute()
        if (!response.isSuccessful) {
            throw Exception("Failed to fetch PR body: ${response.code} ${response.message}")
        }

        val bodyString = response.body.string()
        val json = JSONObject(bodyString)
        runCatching {
            json.getString("body")
        }.getOrNull() ?: ""
    } catch (e: Exception) {
        println("❌ Failed to fetch PR body: ${e.message}")
        error("Failed to fetch PR body: ${e.message}")
    }
    println("✅ Fetched PR body successfully")

    return prBody
}

/**
 * Parse command line arguments into a map of flag names to values
 */
private fun parseArgs(args: Array<String>): Map<String, String> {
    val result = mutableMapOf<String, String>()
    var i = 0
    
    while (i < args.size) {
        when (args[i]) {
            "--url", "-u" -> {
                if (i + 1 < args.size) {
                    result["url"] = args[i + 1]
                    i += 2
                } else {
                    println("⚠️ Missing value for ${args[i]} flag")
                    i++
                }
            }
            "--model", "-m" -> {
                if (i + 1 < args.size) {
                    result["model"] = args[i + 1]
                    i += 2
                } else {
                    println("⚠️ Missing value for ${args[i]} flag")
                    i++
                }
            }
            else -> {
                // For backward compatibility, treat first positional arg as URL if not specified with a flag
                if (!result.containsKey("url")) {
                    result["url"] = args[i]
                }
                i++
            }
        }
    }
    
    return result
}
