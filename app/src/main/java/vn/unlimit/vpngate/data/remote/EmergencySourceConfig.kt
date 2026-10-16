package vn.unlimit.vpngate.data.remote

/**
 * Z1-IMPL-B (Roadmap §12.5.2 item B): the domain view of one emergency
 * source. Mirrors the entity one-to-one; the retrieval wiring of item C and
 * the manual-entry merge of item D will consume this value type.
 */
data class EmergencySourceConfig(
    val id: Int,
    val url: String,
    val label: String?,
    val enabled: Boolean,
    val createdAt: Long,
    val updatedAt: Long,
) {
    companion object {
        fun fromEntity(entity: EmergencySourceConfigEntity): EmergencySourceConfig =
            EmergencySourceConfig(
                id = entity.id,
                url = entity.url,
                label = entity.label,
                enabled = entity.enabled,
                createdAt = entity.createdAt,
                updatedAt = entity.updatedAt,
            )
    }

    fun toEntity(): EmergencySourceConfigEntity = EmergencySourceConfigEntity(
        id = id,
        url = url,
        label = label,
        enabled = enabled,
        createdAt = createdAt,
        updatedAt = updatedAt,
    )
}
