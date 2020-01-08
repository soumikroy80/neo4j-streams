package streams

import org.junit.Assume
import org.neo4j.test.rule.DbmsRule
import streams.config.StreamsConfig

fun DbmsRule.setConfig(key: String, value: String): DbmsRule {
    StreamsConfig.registerListener { it.put(key, value) }
    return this
}

fun DbmsRule.start(timeout: Long = 5000): DbmsRule {
    try {
        this.restartDatabase()
    } catch (e: NullPointerException) {
        val before = DbmsRule::class.java.getDeclaredMethod("before")
        before.isAccessible = true
        before.invoke(this)
    }
    Assume.assumeTrue(this.isAvailable(timeout))
    return this
}

fun DbmsRule.shutdownSilently(): DbmsRule {
    try { this.shutdown() } catch (ignored: Exception) {} // the PageCacheLifecycle throws an exception, investigate why
    return this
}
