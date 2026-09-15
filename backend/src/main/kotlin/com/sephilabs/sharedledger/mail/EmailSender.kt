package com.sephilabs.sharedledger.mail

data class OutgoingEmail(val to: String, val subject: String, val body: String)

/** One delivery attempt. Failures come back as `ok=false` with the server's response instead of being thrown,
 *  so callers log and move on — the same fire-and-log contract as the Telegram client, no retry queue. */
data class SendResult(val ok: Boolean, val description: String?)

interface EmailSender {
    fun send(mail: OutgoingEmail): SendResult
}

/** Bound when SMTP is not configured. Nothing should reach it: every mail-backed feature is hidden then. */
object DisabledEmailSender : EmailSender {
    override fun send(mail: OutgoingEmail): SendResult = SendResult(false, "SMTP not configured")
}
