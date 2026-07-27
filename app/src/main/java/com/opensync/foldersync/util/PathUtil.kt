package com.opensync.foldersync.util

/**
 * Helpers for manipulating '/'-separated paths used throughout the sync engine.
 * All relative paths use forward slashes and have no leading/trailing slash.
 */
object PathUtil {

    fun normalize(path: String): String {
        var p = path.replace('\\', '/')
        while (p.contains("//")) p = p.replace("//", "/")
        if (p.length > 1 && p.endsWith("/")) p = p.dropLast(1)
        return p
    }

    /** Join a provider root with a relative path. */
    fun join(base: String, rel: String): String {
        val b = normalize(base)
        val r = normalize(rel.trim('/'))
        if (r.isEmpty() || r == ".") return b
        return normalize("$b/$r")
    }

    /** Join a base URL with a relative path (does not touch the scheme). */
    fun joinUrl(base: String, rel: String): String {
        val b = base.trimEnd('/')
        val r = rel.trim('/')
        return if (r.isEmpty()) b else "$b/$r"
    }

    fun childRel(parentRel: String, name: String): String {
        val p = parentRel.trim('/')
        return if (p.isEmpty()) name else "$p/$name"
    }

    fun name(rel: String): String {
        val r = rel.trim('/')
        val i = r.lastIndexOf('/')
        return if (i < 0) r else r.substring(i + 1)
    }

    /** Every ancestor directory of [rel], shallowest first (excluding [rel] itself). */
    fun ancestors(rel: String): List<String> {
        val parts = rel.trim('/').split('/').filter { it.isNotEmpty() }
        val res = ArrayList<String>()
        var acc = ""
        for (k in 0 until parts.size - 1) {
            acc = if (acc.isEmpty()) parts[k] else "$acc/${parts[k]}"
            res.add(acc)
        }
        return res
    }
}
