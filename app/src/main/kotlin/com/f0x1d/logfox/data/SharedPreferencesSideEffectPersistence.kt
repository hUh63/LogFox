package com.f0x1d.logfox.data

import android.content.Context
import android.content.SharedPreferences
import com.f0x1d.logfox.core.tea.PersistedSideEffect
import com.f0x1d.logfox.core.tea.SideEffectPersistence
import org.json.JSONArray
import org.json.JSONObject

/**
 * Keeps the reliable side effects that survived a process death.
 *
 * One SharedPreferences entry per screen (namespace = ViewModel class name), because Android
 * applies writes asynchronously and a single big entry would make every screen rewrite the
 * whole file.
 */
class SharedPreferencesSideEffectPersistence(
    context: Context,
    private val namespace: String,
) : SideEffectPersistence {

    private val preferences: SharedPreferences =
        context.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)

    private val preferenceKey = "pending_$namespace"

    override fun loadAll(): List<PersistedSideEffect> {
        val raw = preferences.getString(preferenceKey, null) ?: return emptyList()

        return runCatching {
            val array = JSONArray(raw)
            val items = ArrayList<PersistedSideEffect>(array.length())

            for (index in 0 until array.length()) {
                val item = array.getJSONObject(index)
                val args = item.optJSONObject(KEY_ARGS)
                val map = HashMap<String, String>()

                if (args != null) {
                    args.keys().forEach { key -> map[key] = args.optString(key) }
                }

                items += PersistedSideEffect(
                    key = item.getString(KEY_KEY),
                    args = map,
                    savedAt = item.optLong(KEY_SAVED_AT),
                )
            }

            items
        }.getOrElse { emptyList() }
    }

    override fun replaceAll(items: List<PersistedSideEffect>) {
        if (items.isEmpty()) {
            preferences.edit().remove(preferenceKey).apply()
            return
        }

        val array = JSONArray()

        items.forEach { effect ->
            array.put(
                JSONObject()
                    .put(KEY_KEY, effect.key)
                    .put(KEY_SAVED_AT, effect.savedAt)
                    .put(
                        KEY_ARGS,
                        JSONObject().apply {
                            effect.args.forEach { (key, value) -> put(key, value) }
                        },
                    ),
            )
        }

        preferences.edit().putString(preferenceKey, array.toString()).apply()
    }

    private companion object {
        const val FILE_NAME = "pending_side_effects"
        const val KEY_KEY = "key"
        const val KEY_ARGS = "args"
        const val KEY_SAVED_AT = "savedAt"
    }
}
