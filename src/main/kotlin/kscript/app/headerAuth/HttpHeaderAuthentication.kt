package kscript.app.headerAuth

import org.eclipse.aether.repository.Authentication
import org.eclipse.aether.repository.AuthenticationContext
import org.eclipse.aether.repository.AuthenticationDigest

class HttpHeaderAuthentication(
    var headerKey: String,
    var headerValue: String
) : Authentication {

    override fun fill(context: AuthenticationContext?, key: String?, data: MutableMap<String, String>?) {
        // not called
    }

    override fun digest(digest: AuthenticationDigest?) {
        // not called
    }
}