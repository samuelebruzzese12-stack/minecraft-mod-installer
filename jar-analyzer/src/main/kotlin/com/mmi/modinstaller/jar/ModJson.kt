package com.mmi.modinstaller.jar

import com.mmi.modinstaller.core.asJsonObject
import com.mmi.modinstaller.core.list
import com.mmi.modinstaller.core.str

/** Conversione dei metadati in strutture JSON per l'archivio centrale dell'app (e ritorno). */
object ModJson {

    fun toMap(m: ModMetadata, withDependencies: Boolean = true): Map<String, Any?> {
        val map = LinkedHashMap<String, Any?>()
        map["loader"] = m.loader.key
        map["certainty"] = m.certainty.name
        map["id"] = m.id
        m.name?.let { map["name"] = it }
        m.version?.let { map["version"] = it }
        m.description?.let { map["description"] = it }
        if (m.authors.isNotEmpty()) map["authors"] = m.authors
        m.license?.let { map["license"] = it }
        m.homepage?.let { map["homepage"] = it }
        map["environment"] = m.environment.name
        if (m.provides.isNotEmpty()) map["provides"] = m.provides
        map["descriptor"] = m.descriptor
        m.minecraftHint?.let { map["minecraftHint"] = it }
        m.languageLoader?.let { map["languageLoader"] = it }
        if (withDependencies && m.dependencies.isNotEmpty()) map["dependencies"] = m.dependencies.map { depToMap(it) }
        return map
    }

    fun fromMap(map: Map<String, Any?>): ModMetadata? {
        val loader = ModLoader.fromKey(map.str("loader")) ?: return null
        val id = map.str("id") ?: return null
        return ModMetadata(
            loader = loader,
            certainty = enumOrNull<LoaderCertainty>(map.str("certainty")) ?: LoaderCertainty.CERTAIN,
            id = id,
            name = map.str("name"),
            version = map.str("version"),
            description = map.str("description"),
            authors = strings(map["authors"]),
            license = map.str("license"),
            homepage = map.str("homepage"),
            environment = enumOrNull<ModEnvironment>(map.str("environment")) ?: ModEnvironment.BOTH,
            provides = strings(map["provides"]),
            dependencies = map.list("dependencies").orEmpty().mapNotNull { depFromMap(it.asJsonObject()) },
            descriptor = map.str("descriptor") ?: "",
            minecraftHint = map.str("minecraftHint"),
            languageLoader = map.str("languageLoader")
        )
    }

    fun depToMap(d: ModDependency): Map<String, Any?> {
        val map = LinkedHashMap<String, Any?>()
        map["id"] = d.id
        map["kind"] = d.kind.name
        d.range?.let { map["range"] = it }
        map["syntax"] = d.syntax.name
        d.side?.let { map["side"] = it }
        d.reason?.let { map["reason"] = it }
        return map
    }

    fun depFromMap(map: Map<String, Any?>?): ModDependency? {
        if (map == null) return null
        val id = map.str("id") ?: return null
        return ModDependency(
            id = id,
            kind = enumOrNull<DependencyKind>(map.str("kind")) ?: DependencyKind.REQUIRED,
            range = map.str("range"),
            syntax = enumOrNull<RangeSyntax>(map.str("syntax")) ?: RangeSyntax.SEMVER,
            side = map.str("side"),
            reason = map.str("reason")
        )
    }

    private fun strings(v: Any?): List<String> = (v as? List<*>)?.mapNotNull { it as? String }.orEmpty()

    private inline fun <reified E : Enum<E>> enumOrNull(name: String?): E? =
        if (name == null) null else enumValues<E>().firstOrNull { it.name == name }
}
