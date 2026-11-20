package com.larp.launcher.domain.model

data class DevKitEntry(
    val kind: Char,
    val assembly: String,
    val owner: String,
    val member: String,
    val signature: String,
    val address: String,
    val offset: String,
    val isStatic: Boolean
) {
    val title: String
        get() = if (kind == 'C') owner else "$owner.$member"

    val location: String
        get() = when (kind) {
            'M' -> if (address.isBlank()) "sem endereço" else address
            'F' -> "offset $offset"
            else -> "Il2CppClass* $address"
        }

    val copyText: String
        get() = when (kind) {
            'M' -> "$title $address"
            'F' -> "$title $offset"
            else -> owner
        }
}
