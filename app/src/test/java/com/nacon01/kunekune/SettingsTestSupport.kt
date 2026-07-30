package com.nacon01.kunekune

class TestStringPreferences : StringPreferenceStore {
    val values = mutableMapOf<String, String>()

    override fun getString(key: String): String? = values[key]
    override fun putString(key: String, value: String) {
        values[key] = value
    }
    override fun remove(key: String) {
        values.remove(key)
    }
}
