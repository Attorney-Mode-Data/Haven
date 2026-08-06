package sh.haven.core.rdp

/** An RDP logon identity split into the two fields the Client Info PDU carries. */
data class RdpLogonName(val username: String, val domain: String)

/**
 * Split a qualified RDP username into the username and domain fields (#461).
 *
 * Windows will not auto-logon a Microsoft account unless the name is qualified
 * as `MicrosoftAccount\you@example.com`; a local account on a domain-joined box
 * often needs `.\you`. Users type those into Haven's username box because that
 * is what every guide tells them to, and Haven passed the whole string through
 * as the username with an empty domain — so Windows accepted the credentials at
 * the transport and then showed the lock screen anyway.
 *
 * The rule is deliberately narrow: **split what the user already qualified, and
 * never invent a qualification.** A bare `user@example.com` is left alone,
 * because it is indistinguishable from an Active Directory UPN — prefixing
 * `MicrosoftAccount\` on sight would log AD users out of their own domain to fix
 * a Microsoft-account case we cannot detect from the string. When someone means
 * a Microsoft account they can say so, and this makes saying so work.
 *
 * An explicit domain field always wins: if the profile already carries one, the
 * username is passed through untouched, so this can never override a choice the
 * user made deliberately.
 */
fun qualifyRdpLogon(rawUsername: String, rawDomain: String): RdpLogonName {
    val username = rawUsername.trim()
    val domain = rawDomain.trim()

    // The user filled in the domain field — respect it, whatever the username
    // looks like. Splitting here would silently discard what they typed.
    if (domain.isNotEmpty()) return RdpLogonName(username, domain)

    // Split on the FIRST backslash only, matching FreeRDP's
    // freerdp_parse_username_ptr (client/common/cmdline.c), whose comment
    // states the same rule about '@': "Do not break up the name for '@'; both
    // credSSP and the ClientInfo PDU expect 'user@corp.net' to be transmitted
    // as username 'user@corp.net', domain empty".
    val sep = username.indexOf('\\')
    if (sep < 0) {
        // Bare `user` and UPN `user@example.com` — both already correct.
        return RdpLogonName(username, "")
    }

    val prefix = username.substring(0, sep)
    val account = username.substring(sep + 1)
    // `DOMAIN\` with nothing after it is a half-typed entry, not a
    // qualification. FreeRDP would yield an empty username here; keeping the
    // raw text at least fails with something the user can recognise.
    if (account.isEmpty()) return RdpLogonName(username, "")

    // A leading `\user` leaves an empty domain, which is what FreeRDP produces
    // and what Windows accepts — dropping the stray separator is the point.
    return RdpLogonName(account, prefix)
}

/**
 * A username's *shape* for logs, never the username itself.
 *
 * Haven's logs are meant to be attached to bug reports, and a reporter on #477
 * deleted three of them after noticing his Windows account name was in every
 * one. Anything written here can end up in a public issue, so it has to be
 * useful to whoever reads it and worthless to anyone else.
 *
 * The shape is kept because it has already mattered: #461 was sspi truncating
 * `me@example.com` at the `@` under NLA, and "does this name contain an @"
 * was the question that identified it. Length and qualification style answer
 * that without naming anybody.
 */
fun redactUsername(username: String): String = when {
    username.isEmpty() -> "<none>"
    username.contains('@') -> "<${username.length} chars, upn>"
    username.contains('\\') -> "<${username.length} chars, domain\\user>"
    else -> "<${username.length} chars>"
}
