package com.mytetz.graph.scripts

import com.mytetz.account.AccountRepository
import com.mytetz.account.MagicLinkService
import com.mytetz.account.MailSender
import com.mytetz.persistence.Mongo
import java.io.File
import java.time.Instant
import kotlin.system.exitProcess

/**
 * Issue #182's owner command: make one sign-in link for the test learner of the listing capture.
 * **The owner runs this command. An agent never runs it against production.**
 *
 * ## The safety rule this file follows
 *
 * The command serves one address only: [CAPTURE_EMAIL]. It refuses each other address, so it never
 * makes a sign-in link for a real learner. The check runs before the command reads the environment
 * or opens a database connection.
 *
 * ## How the owner runs this
 *
 * **The dry run. Writes nothing. This is the default:**
 * ```
 * ./gradlew :backend:graph:makeCaptureSignInLink
 * ```
 *
 * **The write. Inserts one token row and writes the link to a file:**
 * ```
 * MONGODB_URI="<the real connection string>" ./gradlew :backend:graph:makeCaptureSignInLink --args="--write"
 * ```
 *
 * The command never prints the link. It prints the path of the file and the expiry time. The
 * default file is `build/capture/sign-in-link.txt` at the repository root, and `.gitignore` ignores
 * the `build/` folder. `--out <path>` names a different file. The owner keeps that file out of git.
 * The token expires after 15 minutes ([MagicLinkService.TTL_MILLIS]).
 *
 * ## How it reuses the token code
 *
 * The command calls [MagicLinkService.request], so the token and its hash come from the code that
 * the server uses. A [MailSender] that keeps the link in memory takes the place of the real mail
 * sender. No mail leaves this process.
 */
fun main(args: Array<String>) {
    exitProcess(
        runMain {
            val command = parseCaptureLinkArgs(args.toList())
            when {
                command is CaptureLinkCommand.InvalidArgs -> {
                    System.err.println("Error: ${command.message}")
                    1
                }
                command is CaptureLinkCommand.Make && !command.write -> {
                    println(renderDryRun(command, Instant.now().toEpochMilli()))
                    0
                }
                !requireMongoUriSet() -> 1
                else -> runOwnerScript { mongo -> write(mongo, command as CaptureLinkCommand.Make) }
            }
        },
    )
}

/** The one address that this command serves. */
internal const val CAPTURE_EMAIL = "listing-capture@mytetz.com"

internal const val CAPTURE_BASE_URL = "https://mytetz.com"

internal const val DEFAULT_LINK_FILE = "build/capture/sign-in-link.txt"

internal sealed interface CaptureLinkCommand {
    data class Make(val email: String, val out: String, val write: Boolean) : CaptureLinkCommand
    data class InvalidArgs(val message: String) : CaptureLinkCommand
}

internal fun parseCaptureLinkArgs(args: List<String>): CaptureLinkCommand {
    val emailIndex = args.indexOf("--email")
    val rawEmail = if (emailIndex == -1) CAPTURE_EMAIL else args.getOrNull(emailIndex + 1).orEmpty()
    val email = MagicLinkService.normaliseEmail(rawEmail)
    if (email != CAPTURE_EMAIL) {
        return CaptureLinkCommand.InvalidArgs("this command serves only the test address $CAPTURE_EMAIL")
    }
    val outIndex = args.indexOf("--out")
    val out = if (outIndex == -1) DEFAULT_LINK_FILE else args.getOrNull(outIndex + 1).orEmpty()
    if (out.isBlank()) return CaptureLinkCommand.InvalidArgs("--out needs a file path")
    return CaptureLinkCommand.Make(email = email, out = out, write = "--write" in args)
}

internal fun renderDryRun(command: CaptureLinkCommand.Make, nowMillis: Long): String = buildString {
    appendLine("Dry run. Nothing is written. Add --write to insert the token row.")
    appendLine("Test address: ${command.email}")
    appendLine("Link file: ${command.out}")
    append("The token would expire at about ${Instant.ofEpochMilli(nowMillis + MagicLinkService.TTL_MILLIS)}.")
}

/** Keeps the link in memory. This sender sends no mail. */
private class CapturingMailSender : MailSender {
    var link: String? = null

    override suspend fun sendMagicLink(email: String, link: String) {
        this.link = link
    }
}

private suspend fun write(mongo: Mongo, command: CaptureLinkCommand.Make): Int {
    val file = File(command.out)
    file.absoluteFile.parentFile?.mkdirs()
    val sender = CapturingMailSender()
    val now = System.currentTimeMillis()
    val service = MagicLinkService(AccountRepository(mongo.database), sender, CAPTURE_BASE_URL, clock = { now })
    service.request(command.email)
    val link = sender.link ?: run {
        System.err.println("Error: the service made no link")
        return 1
    }
    file.writeText(link)
    println("Inserted one token row for ${command.email}.")
    println("Link file: ${file.absolutePath}")
    println("The link expires at ${Instant.ofEpochMilli(now + MagicLinkService.TTL_MILLIS)}.")
    return 0
}
