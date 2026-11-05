package com.mmi.modinstaller.convert.code

import com.mmi.modinstaller.bytecode.AltV
import com.mmi.modinstaller.bytecode.ArrV
import com.mmi.modinstaller.bytecode.Call
import com.mmi.modinstaller.bytecode.Desc
import com.mmi.modinstaller.bytecode.Interp
import com.mmi.modinstaller.bytecode.IntV
import com.mmi.modinstaller.bytecode.LamV
import com.mmi.modinstaller.bytecode.Obj
import com.mmi.modinstaller.bytecode.Op
import com.mmi.modinstaller.bytecode.Ref
import com.mmi.modinstaller.bytecode.StaticV
import com.mmi.modinstaller.bytecode.StrV
import com.mmi.modinstaller.bytecode.Unk
import com.mmi.modinstaller.bytecode.V
import com.mmi.modinstaller.bytecode.int
import com.mmi.modinstaller.bytecode.num
import com.mmi.modinstaller.convert.Rl

// ------------------------------------------------------------------------------------------------------------ dati

class TierFacts(val name: String?, val uses: Int?, val speed: Double?, val attackBonus: Double?, val level: Int?, val enchantability: Int?)
class EffectFacts(val effect: String, val duration: Int, val amplifier: Int, val chance: Double)
class FoodFacts(val nutrition: Int?, val saturation: Double?, val alwaysEdible: Boolean, val fast: Boolean, val effects: List<EffectFacts>)

/** Quello che il codice dice di un oggetto. I valori null non si sono potuti leggere. */
class ItemFacts(
    val id: Rl,
    /** Classi della mod e prima classe di Minecraft (es. [RubySword, SwordItem]). */
    val classes: List<String>,
    /** Ruolo ricavato dalla classe di Minecraft da cui deriva (sword, armor, food, spawn_egg, block_item…). */
    val role: String,
    val maxStack: Int?,
    val durability: Int?,
    val fireResistant: Boolean,
    val rarity: String?,
    val food: FoodFacts?,
    val tier: TierFacts?,
    /** Danno in più dell'attacco (come in Java: modificatore dell'arma + bonus del materiale). */
    val attackDamage: Double?,
    /** Modificatore della velocità d'attacco Java (es. -2.4): Bedrock non ha la ricarica degli attacchi. */
    val attackSpeed: Double?,
    val armorSlot: String?,
    val armorDefense: Int?,
    val armorToughness: Double?,
    val armorKnockback: Double?,
    val enchantability: Int?,
    val blockId: Rl?,
    val spawnEggEntity: Rl?,
    val eggColors: Pair<Int, Int>?,
    val craftRemainder: Rl?
)

class BlockFacts(
    val id: Rl,
    val classes: List<String>,
    val role: String,
    val hardness: Double?,
    val resistance: Double?,
    val light: Int?,
    /** Suono Bedrock equivalente, se il blocco usa un SoundType di Minecraft. */
    val sound: String?,
    val noOcclusion: Boolean,
    val noCollision: Boolean,
    val friction: Double?,
    val speedFactor: Double?,
    val jumpFactor: Double?,
    val randomTicks: Boolean,
    val requiresTool: Boolean,
    val instabreak: Boolean,
    val copiedFrom: String?,
    val xp: Pair<Int, Int>?,
    val renderLayer: String?,
    val hasBlockEntity: Boolean
)

class GoalFacts(val priority: Int, val goal: String, val custom: String?, val args: List<V>, val target: Boolean)
class CubeFacts(val u: Int, val v: Int, val x: Double, val y: Double, val z: Double, val w: Double, val h: Double, val d: Double, val inflate: Double, val mirror: Boolean)
class PartFacts(
    val name: String, val x: Double, val y: Double, val z: Double, val rx: Double, val ry: Double, val rz: Double,
    val cubes: List<CubeFacts>, val children: List<PartFacts>
)
class KeyFacts(val time: Double, val x: Double, val y: Double, val z: Double, val interpolation: String)
class ChannelFacts(val bone: String, val target: String, val keys: List<KeyFacts>)
class AnimFacts(val name: String, val length: Double, val looping: Boolean, val channels: List<ChannelFacts>, val role: String)
class ModelFacts(val modelClass: String, val texWidth: Int, val texHeight: Int, val root: PartFacts, val animations: List<AnimFacts>)

class EntityFacts(
    val id: Rl,
    val classes: List<String>,
    /** Classe di Minecraft da cui deriva la creatura (Monster, Animal, TamableAnimal…). */
    val vanillaBase: String,
    val category: String?,
    val width: Double?,
    val height: Double?,
    val fireImmune: Boolean,
    val attributes: Map<String, Double>,
    val goals: List<GoalFacts>,
    val texture: Rl?,
    val model: ModelFacts?,
    val shadow: Double?,
    val rendererClass: String?,
    /** Il renderer è di GeckoLib: il modello sta nei file della mod. */
    val geckoLib: Boolean,
    val geoModel: Rl?,
    val geoAnimation: Rl?
)

/** Tutto quello che si è capito dal codice di una mod. */
class ModCodeFacts(
    val analysis: CodeAnalysis,
    val items: Map<Rl, ItemFacts>,
    val blocks: Map<Rl, BlockFacts>,
    val entities: Map<Rl, EntityFacts>
)

// ------------------------------------------------------------------------------------------------------------ lettura

/** Ricava dai valori costruiti dalle registrazioni le proprietà di oggetti, blocchi e creature. */
object CodeFactsReader {

    fun read(a: CodeAnalysis): ModCodeFacts {
        val r = Reader(a)
        val items = LinkedHashMap<Rl, ItemFacts>()
        val blocks = LinkedHashMap<Rl, BlockFacts>()
        val entities = LinkedHashMap<Rl, EntityFacts>()
        for (reg in a.byRegistry("block")) runCatching { r.block(reg) }.getOrNull()?.let { blocks[reg.id] = it }
        for (reg in a.byRegistry("item")) runCatching { r.item(reg) }.getOrNull()?.let { items[reg.id] = it }
        for (reg in a.byRegistry("entity_type")) runCatching { r.entity(reg) }.getOrNull()?.let { entities[reg.id] = it }
        return ModCodeFacts(a, items, blocks, entities)
    }

    private class Reader(val a: CodeAnalysis) {
        val ip: Interp = a.interp
        val sem = a.semantics

        // -------------------------------------------------------------------------------------------- utilità

        fun simple(c: String) = c.substringAfterLast('/').substringAfterLast('$')

        /** Classi della mod e prima classe non della mod. */
        fun chain(o: Obj): List<String> {
            val ch = ip.superChain(o.type)
            val sc = o.superCall?.owner
            return if (sc != null && ch.last() != sc && ip.classes.find(sc) == null && sc !in ch) ch + sc else ch
        }

        fun role(classes: List<String>, roles: Map<String, String>): String {
            for (c in classes) roles[simple(c)]?.let { return it }
            return roles.getValue(if (roles === JavaVanilla.ITEM_ROLES) "Item" else "Block")
        }

        /** Gli argomenti dei costruttori dell'oggetto (il suo e quello di Minecraft a cui arriva). */
        fun ctorArgs(o: Obj): List<V> = o.args + (o.superCall?.args ?: emptyList())

        /** Cerca, anche dentro gli argomenti, un oggetto di uno dei tipi dati. */
        fun find(vs: List<V>, depth: Int = 0, pred: (Obj) -> Boolean): Obj? {
            for (v in vs) {
                val o = v as? Obj ?: continue
                if (pred(o)) return o
            }
            if (depth >= 2) return null
            for (v in vs) {
                val o = v as? Obj ?: continue
                find(o.args + (o.origin?.args ?: emptyList()) + (o.superCall?.args ?: emptyList()), depth + 1, pred)?.let { return it }
            }
            return null
        }

        fun calls(o: Obj?, vararg names: String): List<Call> = o?.calls?.filter { it.name in names } ?: emptyList()
        fun call(o: Obj?, vararg names: String): Call? = calls(o, *names).lastOrNull()

        fun num(v: V?): Double? = v?.num()

        fun resolve(v: V?): V? = when (v) {
            null -> null
            is LamV -> runCatching { ip.callLambda(v, emptyList(), 1) }.getOrNull()
            is Obj -> (v.ext as? RegHandle)?.reg?.value ?: v
            is AltV -> v.options.firstOrNull()
            else -> v
        }

        fun idOf(v: V?): Rl? {
            val x = resolve(v)
            if (x is Obj) a.idOf(x)?.let { return it }
            if (v is Obj) ((v.ext as? RegHandle)?.reg?.id)?.let { return it }
            if (x is StaticV && (x.owner.endsWith("/Items") || x.owner.endsWith("/Blocks") || x.owner.endsWith("/EntityType"))) {
                return Rl("minecraft", x.name.lowercase())
            }
            return null
        }

        /** Chiama un metodo senza argomenti (o con [args]) su un oggetto della mod e ne restituisce il valore. */
        fun eval(o: V, name: String, desc: String, args: List<V> = emptyList()): V? {
            if (o !is Obj) return null
            if (ip.classes.find(o.type) == null) return null
            return runCatching { ip.dispatch(Op.INVOKEVIRTUAL, Ref(o.type, name, desc), o, args, 1) }.getOrNull()
        }

        // -------------------------------------------------------------------------------------------- oggetti

        fun tier(v: V?): TierFacts? {
            val t = resolve(v) ?: return null
            if (t is StaticV) {
                val vt = JavaVanilla.TIERS[t.name] ?: return null
                return TierFacts(t.name.lowercase(), vt.uses, vt.speed, vt.attackBonus, vt.level, vt.enchantability)
            }
            if (t !is Obj) return null
            val type = t.type
            // Record ToolMaterial (1.21.2+) e SimpleTier/ForgeTier: valori nel costruttore.
            if (type == "net/minecraft/world/item/ToolMaterial") {
                val g = t.args
                return TierFacts(null, g.getOrNull(1)?.int(), num(g.getOrNull(2)), num(g.getOrNull(3)), null, g.getOrNull(4)?.int())
            }
            if (type.endsWith("/SimpleTier")) {
                val g = t.args
                return TierFacts(null, g.getOrNull(1)?.int(), num(g.getOrNull(2)), num(g.getOrNull(3)), null, g.getOrNull(4)?.int())
            }
            if (type.endsWith("/ForgeTier")) {
                val g = t.args
                return TierFacts(null, g.getOrNull(1)?.int(), num(g.getOrNull(2)), num(g.getOrNull(3)), g.getOrNull(0)?.int(), g.getOrNull(4)?.int())
            }
            // Materiale della mod (enum o classe): si leggono i suoi metodi.
            val uses = eval(t, "getUses", "()I")?.int()
            val speed = num(eval(t, "getSpeed", "()F"))
            val bonus = num(eval(t, "getAttackDamageBonus", "()F"))
            val level = eval(t, "getLevel", "()I")?.int()
            val ench = eval(t, "getEnchantmentValue", "()I")?.int()
            if (uses == null && speed == null && bonus == null) return null
            val name = (t.args.firstOrNull() as? StrV)?.v?.lowercase()
            return TierFacts(name, uses, speed, bonus, level, ench)
        }

        fun food(v: V?): FoodFacts? {
            val f = resolve(v) as? Obj ?: return null
            val b = (if (f.origin?.name == "build") f.originReceiver as? Obj else f) ?: return null
            val nutrition = call(b, "nutrition")?.args?.firstOrNull()?.int()
            val sat = num(call(b, "saturationModifier", "saturationMod")?.args?.firstOrNull())
            val always = calls(b, "alwaysEdible", "alwaysEat").isNotEmpty()
            val fast = calls(b, "fast").isNotEmpty()
            val effects = calls(b, "effect").mapNotNull { c ->
                val inst = resolve(c.args.firstOrNull()) as? Obj ?: return@mapNotNull null
                effect(inst, num(c.args.getOrNull(1)) ?: 1.0)
            }
            if (nutrition == null && sat == null && effects.isEmpty()) return null
            return FoodFacts(nutrition, sat, always, fast, effects)
        }

        fun effect(inst: Obj, chance: Double): EffectFacts? {
            if (!inst.type.endsWith("/MobEffectInstance")) return null
            val e = inst.args.firstOrNull()
            val id = when (val x = resolve(e)) {
                is StaticV -> JavaVanilla.EFFECTS[x.name]?.let { "minecraft:$it" }
                is Obj -> a.idOf(x)?.toString() ?: ((e as? Obj)?.ext as? RegHandle)?.reg?.id?.toString()
                else -> null
            } ?: return null
            return EffectFacts(id, inst.args.getOrNull(1)?.int() ?: 0, inst.args.getOrNull(2)?.int() ?: 0, chance)
        }

        fun item(reg: CodeRegistration): ItemFacts? {
            val o = resolve(reg.value) as? Obj ?: return null
            val classes = chain(o)
            var role = role(classes, JavaVanilla.ITEM_ROLES)
            val args = ctorArgs(o)
            val props = find(args) { it.type.endsWith("Item\$Properties") || it.type.endsWith("/FabricItemSettings") || it.type.endsWith("Item\$Settings") }
            val maxStack = call(props, "stacksTo")?.args?.firstOrNull()?.int()
            val durability = call(props, "durability", "defaultDurability")?.args?.firstOrNull()?.int()
            val fire = calls(props, "fireResistant").isNotEmpty()
            val rarity = (call(props, "rarity")?.args?.firstOrNull() as? StaticV)?.name?.lowercase()
            val food = call(props, "food")?.args?.firstOrNull()?.let { food(it) }
            if (food != null && role == "item") role = "food"
            val remainder = call(props, "craftRemainder")?.args?.firstOrNull()?.let { idOf(it) }
            val enchantable = call(props, "enchantable")?.args?.firstOrNull()?.int()

            // ---- armi e attrezzi
            var tier: TierFacts? = null
            var dmg: Double? = null
            var spd: Double? = null
            val toolCall = call(props, "sword", "pickaxe", "axe", "shovel", "hoe", "tool")
            if (toolCall != null) {
                tier = tier(toolCall.args.firstOrNull())
                dmg = num(toolCall.args.getOrNull(1))?.let { it + (tier?.attackBonus ?: 0.0) }
                spd = num(toolCall.args.getOrNull(2))
                if (role == "item") role = toolCall.name
            } else if (role in setOf("sword", "pickaxe", "axe", "shovel", "hoe", "digger", "tiered")) {
                val sc = o.superCall?.args ?: emptyList()
                tier = tier(sc.firstOrNull())
                val attr = find(listOfNotNull(call(props, "attributes")?.args?.firstOrNull())) { it.origin?.name == "createAttributes" }
                if (attr != null) {
                    val g = attr.origin!!.args
                    if (tier == null) tier = tier(g.firstOrNull())
                    dmg = num(g.getOrNull(1))?.let { it + (tier?.attackBonus ?: 0.0) }
                    spd = num(g.getOrNull(2))
                } else if (sc.size >= 4) {
                    dmg = num(sc[1])?.let { it + (tier?.attackBonus ?: 0.0) }
                    spd = num(sc[2])
                }
            }

            // ---- armature
            var slot: String? = null
            var defense: Int? = null
            var tough: Double? = null
            var kb: Double? = null
            var armorEnch: Int? = null
            var armorDur: Int? = null
            val humanoid = call(props, "humanoidArmor", "wolfArmor", "horseArmor")
            if (role == "armor" || humanoid != null) {
                role = "armor"
                val src = humanoid?.args ?: o.superCall?.args ?: o.args
                val typeV = src.firstOrNull { it is StaticV && (it.owner.endsWith("ArmorItem\$Type") || it.owner.endsWith("/ArmorType")) } as? StaticV
                slot = typeV?.name
                val matV = src.firstOrNull { it !is StaticV || !(it.owner.endsWith("ArmorItem\$Type") || it.owner.endsWith("/ArmorType")) }
                val mat = resolve(matV)
                val idx = JavaVanilla.ARMOR_ORDER.indexOf(slot)
                when {
                    mat is StaticV -> JavaVanilla.ARMOR[mat.name]?.let { m ->
                        defense = m.defense.getOrNull(idx); tough = m.toughness; kb = m.knockback; armorEnch = m.enchantability
                        armorDur = JavaVanilla.ARMOR_BASE[slot]?.let { it * m.durabilityFactor }
                    }
                    mat is Obj && mat.type.endsWith("/ArmorMaterial") -> {
                        // 1.20.5+: ArmorMaterial(Map difesa, incantabilità, suono, riparazione, strati, robustezza, contraccolpo)
                        // 1.21.2+: ArmorMaterial(durata, Map difesa, incantabilità, suono, robustezza, contraccolpo, riparazione, aspetto)
                        val g = mat.args
                        val modern = g.firstOrNull()?.int() != null
                        val mapObj = (if (modern) g.getOrNull(1) else g.getOrNull(0)) as? Obj
                        if (mapObj != null && typeV != null) defense = ip.mapGet(mapObj, typeV)?.int()
                        armorEnch = (if (modern) g.getOrNull(2) else g.getOrNull(1))?.int()
                        tough = num(if (modern) g.getOrNull(4) else g.getOrNull(5))
                        kb = num(if (modern) g.getOrNull(5) else g.getOrNull(6))
                        if (modern) armorDur = g.firstOrNull()?.int()?.let { f -> JavaVanilla.ARMOR_BASE[slot]?.let { it * f } }
                    }
                    mat is Obj && typeV != null -> {
                        // Materiale della mod (1.20.1): metodi dell'interfaccia ArmorMaterial.
                        defense = eval(mat, "getDefenseForType", "(Lnet/minecraft/world/item/ArmorItem\$Type;)I", listOf(typeV))?.int()
                        armorDur = eval(mat, "getDurabilityForType", "(Lnet/minecraft/world/item/ArmorItem\$Type;)I", listOf(typeV))?.int()
                        armorEnch = eval(mat, "getEnchantmentValue", "()I")?.int()
                        tough = num(eval(mat, "getToughness", "()F"))
                        kb = num(eval(mat, "getKnockbackResistance", "()F"))
                    }
                }
            }

            // ---- uova e oggetti-blocco
            var egg: Rl? = null
            var colors: Pair<Int, Int>? = null
            var block: Rl? = null
            when (role) {
                "spawn_egg" -> {
                    val g = o.superCall?.args ?: o.args
                    egg = idOf(g.firstOrNull())
                    val c1 = g.getOrNull(1)?.int(); val c2 = g.getOrNull(2)?.int()
                    if (c1 != null && c2 != null) colors = c1 to c2
                }
                "block_item" -> block = idOf((o.superCall?.args ?: o.args).firstOrNull())
            }
            return ItemFacts(
                reg.id, classes, role, maxStack, durability ?: armorDur ?: tier?.uses, fire, rarity, food, tier, dmg, spd,
                slot?.lowercase(), defense, tough, kb, enchantable ?: armorEnch ?: tier?.enchantability, block, egg, colors, remainder
            )
        }

        // -------------------------------------------------------------------------------------------- blocchi

        fun block(reg: CodeRegistration): BlockFacts? {
            val o = resolve(reg.value) as? Obj ?: return null
            val classes = chain(o)
            val role = role(classes, JavaVanilla.BLOCK_ROLES)
            val args = ctorArgs(o)
            val props = find(args) { it.type.endsWith("BlockBehaviour\$Properties") || it.type.endsWith("/FabricBlockSettings") || it.type.endsWith("AbstractBlock\$Settings") }
            var copied: String? = null
            var base: JavaVanilla.BlockDef? = null
            props?.origin?.let { c ->
                if (c.name in setOf("ofFullCopy", "ofLegacyCopy", "copy", "copyOf")) {
                    val src = resolve(c.args.firstOrNull())
                    when (src) {
                        is StaticV -> { copied = src.name.lowercase(); base = JavaVanilla.BLOCKS[src.name] }
                        is Obj -> a.idOf(src)?.let { copied = it.toString() }
                        else -> {}
                    }
                }
            }
            var hardness = base?.hardness
            var resistance = base?.resistance
            call(props, "strength")?.let { c ->
                hardness = num(c.args.getOrNull(0)) ?: hardness
                resistance = num(c.args.getOrNull(1)) ?: num(c.args.getOrNull(0)) ?: resistance
            }
            call(props, "destroyTime", "hardness")?.let { hardness = num(it.args.firstOrNull()) ?: hardness }
            call(props, "explosionResistance", "resistance")?.let { resistance = num(it.args.firstOrNull()) ?: resistance }
            if (calls(props, "instabreak", "breakInstantly").isNotEmpty()) { hardness = 0.0; resistance = 0.0 }
            var light: Int? = base?.light
            call(props, "lightLevel", "luminance")?.args?.firstOrNull()?.let { fn ->
                light = when (fn) {
                    is LamV -> runCatching { ip.callLambda(fn, listOf(Obj("net/minecraft/world/level/block/state/BlockState").also { it.initialized = true }), 1) }.getOrNull()?.int()
                    else -> fn.int()
                } ?: light
            }
            var sound: String? = base?.sound
            call(props, "sound", "sounds")?.args?.firstOrNull()?.let { s ->
                sound = (s as? StaticV)?.let { JavaVanilla.SOUNDS[it.name] } ?: sound
            }
            val xp = if (role == "ore") {
                val p = args.firstOrNull { it is Obj && (it.origin?.name == "of" || it.type.endsWith("IntProvider") || it.type.endsWith("UniformInt") || it.type.endsWith("ConstantInt")) } as? Obj
                p?.origin?.args?.let { g -> val lo = g.getOrNull(0)?.int(); val hi = g.getOrNull(1)?.int() ?: lo; if (lo != null && hi != null) lo to hi else null }
            } else null
            val layer = sem.renderLayers.firstOrNull { it.first === o || (it.first as? Obj)?.let { x -> a.idOf(x) } == reg.id }?.second
            val entityBlock = role == "entity_block" || role in setOf("chest", "furnace", "container", "sign", "bed", "banner", "head", "spawner", "campfire") ||
                classes.any { c -> ip.classes.find(c)?.interfaces?.any { it.endsWith("/EntityBlock") } == true }
            return BlockFacts(
                reg.id, classes, role, hardness, resistance, light, sound,
                calls(props, "noOcclusion", "nonOpaque").isNotEmpty(), calls(props, "noCollission", "noCollision").isNotEmpty(),
                num(call(props, "friction", "slipperiness")?.args?.firstOrNull()), num(call(props, "speedFactor", "velocityMultiplier")?.args?.firstOrNull()),
                num(call(props, "jumpFactor", "jumpVelocityMultiplier")?.args?.firstOrNull()), calls(props, "randomTicks", "ticksRandomly").isNotEmpty(),
                calls(props, "requiresCorrectToolForDrops", "requiresTool").isNotEmpty() || (base?.requiresTool == true && copied != null),
                calls(props, "instabreak", "breakInstantly").isNotEmpty(), copied, xp, layer, entityBlock
            )
        }

        // -------------------------------------------------------------------------------------------- creature

        fun entity(reg: CodeRegistration): EntityFacts? {
            val typeObj = resolve(reg.value) as? Obj ?: return null
            val builder = (typeObj.originReceiver as? Obj) ?: typeObj
            val origin = builder.origin
            val category = origin?.args?.firstOrNull { it is StaticV && (it.owner.endsWith("/MobCategory") || it.owner.endsWith("/SpawnGroup")) }?.let { (it as StaticV).name }
            val factory = origin?.args?.firstOrNull { it is LamV } as? LamV
            var width: Double? = null
            var height: Double? = null
            call(builder, "sized", "dimensions")?.let { c ->
                val g = c.args
                if (g.size >= 2) { width = num(g[0]); height = num(g[1]) }
                else (g.firstOrNull() as? Obj)?.origin?.args?.let { d -> width = num(d.getOrNull(0)); height = num(d.getOrNull(1)) }
            }
            val fireImmune = calls(builder, "fireImmune").isNotEmpty()

            // Classe della creatura: quella costruita dalla fabbrica (Entity::new o una lambda).
            var entityClass: String? = when {
                factory == null -> null
                factory.impl.kind == com.mmi.modinstaller.bytecode.Handle.NEW_INVOKE_SPECIAL -> factory.impl.owner
                else -> (runCatching { ip.callLambda(factory, listOf(typeObj, Obj("net/minecraft/world/level/Level")), 1) }.getOrNull() as? Obj)?.type
            }
            if (entityClass == "java/lang/Object") entityClass = null
            val classes = entityClass?.let { ip.superChain(it) } ?: emptyList()
            val vanillaBase = classes.lastOrNull()?.let { simple(it) } ?: "Entity"

            // Attributi registrati per questo tipo.
            val attrs = LinkedHashMap<String, Double>()
            val supplier = sem.attributes.firstOrNull { (t, _) -> t === typeObj || (t is Obj && a.idOf(t) == reg.id) }?.second
            if (supplier != null) attributes(supplier, attrs)

            // Obiettivi (IA): registerGoals() letto su un oggetto della classe.
            val goals = ArrayList<GoalFacts>()
            if (entityClass != null) goals(entityClass, goals)

            // Renderer, modello e texture.
            var texture: Rl? = null
            var model: ModelFacts? = null
            var shadow: Double? = null
            var rendererClass: String? = null
            var gecko = false
            var geoModel: Rl? = null
            var geoAnim: Rl? = null
            val provider = sem.renderers.firstOrNull { (t, _) -> t === typeObj || (t is Obj && a.idOf(t) == reg.id) }?.second
            val renderer = (provider as? LamV)?.let { p ->
                runCatching { ip.callLambda(p, listOf(Obj("net/minecraft/client/renderer/entity/EntityRendererProvider\$Context").also { it.initialized = true }), 1) }.getOrNull() as? Obj
            }
            if (renderer != null) {
                rendererClass = renderer.type
                val rchain = ip.superChain(renderer.type)
                gecko = rchain.any { it.contains("geckolib") } || renderer.superCall?.owner?.contains("geckolib") == true
                val sc = renderer.superCall?.args ?: renderer.args
                shadow = sc.firstNotNullOfOrNull { (it as? com.mmi.modinstaller.bytecode.FloatV)?.v?.toDouble() }
                val entityArg = Obj(entityClass ?: "net/minecraft/world/entity/Entity").also { it.initialized = true }
                val tex = eval(renderer, "getTextureLocation", "(Lnet/minecraft/world/entity/Entity;)Lnet/minecraft/resources/ResourceLocation;", listOf(entityArg))
                    ?: findTextureMethod(renderer, entityArg)
                texture = sem.rl(resolve(tex))
                val modelObj = sc.firstOrNull { it is Obj && ip.classes.find(it.type) != null && it.type != renderer.type } as? Obj
                if (gecko) {
                    val gm = modelObj ?: sc.firstOrNull { it is Obj && it.type.contains("geckolib") } as? Obj
                    if (gm != null) {
                        geoModel = sem.rl(resolve(evalGeo(gm, "getModelResource")))
                        geoAnim = sem.rl(resolve(evalGeo(gm, "getAnimationResource")))
                        if (texture == null) texture = sem.rl(resolve(evalGeo(gm, "getTextureResource")))
                        if (geoModel == null) {
                            // DefaultedEntityGeoModel(rl): i percorsi si ricavano dall'identificatore.
                            (gm.superCall?.args?.firstOrNull { sem.rl(it) != null } ?: gm.args.firstOrNull { sem.rl(it) != null })?.let { base ->
                                val b = sem.rl(base)!!
                                geoModel = Rl(b.ns, "geo/entity/${b.path}.geo.json"); geoAnim = Rl(b.ns, "animations/entity/${b.path}.animation.json")
                                if (texture == null) texture = Rl(b.ns, "textures/entity/${b.path}.png")
                            }
                        }
                    }
                } else if (modelObj != null) {
                    model = model(modelObj, entityArg)
                }
            }
            return EntityFacts(
                reg.id, classes, vanillaBase, category, width, height, fireImmune, attrs, goals, texture, model, shadow, rendererClass, gecko, geoModel, geoAnim
            )
        }

        private fun findTextureMethod(renderer: Obj, entity: Obj): V? {
            for (c in ip.superChain(renderer.type)) {
                val cf = ip.classes.find(c) ?: break
                val m = cf.methods.firstOrNull { it.name == "getTextureLocation" && it.code != null && it.desc.endsWith(")Lnet/minecraft/resources/ResourceLocation;") } ?: continue
                return runCatching { ip.run(cf, m, renderer, listOf(entity), 1) }.getOrNull()
            }
            return null
        }

        private fun evalGeo(model: Obj, name: String): V? {
            for (c in ip.superChain(model.type)) {
                val cf = ip.classes.find(c) ?: break
                val m = cf.methods.firstOrNull { it.name == name && it.code != null && it.desc.endsWith(")Lnet/minecraft/resources/ResourceLocation;") } ?: continue
                return runCatching { ip.run(cf, m, model, List(Desc.params(m.desc).size) { Unk }, 1) }.getOrNull()
            }
            return null
        }

        fun attributes(v: V, out: MutableMap<String, Double>) {
            var b = resolve(v) as? Obj ?: return
            if (b.origin?.name == "build") (b.originReceiver as? Obj)?.let { b = it }
            b.origin?.name?.let { base -> JavaVanilla.ATTRIBUTE_BASES[base]?.let { out.putAll(it) } }
            for (c in b.calls) {
                if (c.name != "add") continue
                val attr = (resolve(c.args.firstOrNull()) as? StaticV)?.name?.let { JavaVanilla.ATTRIBUTES[it] } ?: continue
                out[attr] = num(c.args.getOrNull(1)) ?: JavaVanilla.ATTRIBUTE_DEFAULTS[attr] ?: continue
            }
        }

        fun goals(entityClass: String, out: MutableList<GoalFacts>) {
            val target = ip.resolve(entityClass, "registerGoals", "()V") ?: return
            val me = Obj(entityClass).also { it.initialized = true }
            runCatching { ip.run(target.first, target.second, me, emptyList(), 1) }
            for ((field, isTarget) in listOf("goalSelector" to false, "targetSelector" to true)) {
                val sel = me.fields[field] as? Obj ?: continue
                for (c in sel.calls) {
                    if (c.name != "addGoal") continue
                    val pr = c.args.getOrNull(0)?.int() ?: continue
                    val g = c.args.getOrNull(1) as? Obj ?: continue
                    val chain = ip.superChain(g.type)
                    val vanilla = simple(chain.last())
                    out.add(GoalFacts(pr, vanilla, if (chain.size > 1) simple(g.type) else null, g.args + (g.superCall?.args ?: emptyList()), isTarget))
                }
            }
        }

        // -------------------------------------------------------------------------------------------- modelli

        fun model(modelObj: Obj, entity: Obj): ModelFacts? {
            // Il modello riceve una ModelPart da ctx.bakeLayer(STRATO): lo strato porta alla LayerDefinition registrata.
            val part = (modelObj.args + (modelObj.superCall?.args ?: emptyList())).firstOrNull { (it as? Obj)?.origin?.name == "bakeLayer" } as? Obj
            val layerLoc = part?.origin?.args?.firstOrNull() ?: return null
            val supplier = sem.layers.firstOrNull { (l, _) -> l === layerLoc || sameLayer(l, layerLoc) }?.second ?: return null
            val def = (if (supplier is LamV) runCatching { ip.callLambda(supplier, emptyList(), 1) }.getOrNull() else supplier) as? Obj ?: return null
            val create = def.origin ?: return null
            val mesh = create.args.firstOrNull() as? Obj ?: return null
            val root = mesh.fields["root"] as? Obj ?: return null
            val tw = create.args.getOrNull(1)?.int() ?: 64
            val th = create.args.getOrNull(2)?.int() ?: 32
            val tree = part(root) ?: return null
            return ModelFacts(modelObj.type, tw, th, tree, animations(modelObj, entity))
        }

        private fun sameLayer(a: V, b: V): Boolean {
            val x = a as? Obj ?: return false
            val y = b as? Obj ?: return false
            val rx = sem.rl(x.args.firstOrNull()); val ry = sem.rl(y.args.firstOrNull())
            return rx != null && rx == ry && (x.args.getOrNull(1) as? StrV)?.v == (y.args.getOrNull(1) as? StrV)?.v
        }

        private fun pose(v: V?): DoubleArray {
            val o = v as? Obj ?: return DoubleArray(6)
            val c = o.origin ?: return DoubleArray(6)
            val g = c.args.map { it.num() ?: 0.0 }
            return when (c.name) {
                "offset" -> doubleArrayOf(g.getOrElse(0) { 0.0 }, g.getOrElse(1) { 0.0 }, g.getOrElse(2) { 0.0 }, 0.0, 0.0, 0.0)
                "offsetAndRotation" -> DoubleArray(6) { g.getOrElse(it) { 0.0 } }
                "rotation" -> doubleArrayOf(0.0, 0.0, 0.0, g.getOrElse(0) { 0.0 }, g.getOrElse(1) { 0.0 }, g.getOrElse(2) { 0.0 })
                else -> DoubleArray(6)
            }
        }

        fun part(o: Obj, depth: Int = 0): PartFacts? {
            val d = o.ext as? PartData ?: return null
            if (depth > 32) return null
            val p = pose(d.pose)
            val cubes = ArrayList<CubeFacts>()
            (d.cubes as? Obj)?.let { b ->
                var u = 0; var v = 0; var mirror = false
                for (c in b.calls) {
                    when (c.name) {
                        "texOffs", "uv" -> { u = c.args.getOrNull(0)?.int() ?: u; v = c.args.getOrNull(1)?.int() ?: v }
                        "mirror" -> mirror = c.args.firstOrNull()?.int()?.let { it != 0 } ?: true
                        "addBox", "cuboid" -> {
                            val g = if (c.args.firstOrNull() is StrV) c.args.drop(1) else c.args
                            val n = g.take(6).map { it.num() }
                            if (n.size == 6 && n.all { it != null }) {
                                val extra = g.drop(6)
                                val inflate = extra.firstNotNullOfOrNull { e -> (e as? Obj)?.takeIf { it.type.endsWith("/CubeDeformation") }?.args?.firstOrNull()?.num() } ?: 0.0
                                val m = extra.firstOrNull { it is IntV }?.int()?.let { it != 0 } ?: mirror
                                cubes.add(CubeFacts(u, v, n[0]!!, n[1]!!, n[2]!!, n[3]!!, n[4]!!, n[5]!!, inflate, m))
                            }
                        }
                    }
                }
            }
            val children = d.children.mapNotNull { part(it, depth + 1) }
            return PartFacts(d.name, p[0], p[1], p[2], p[3], p[4], p[5], cubes, children)
        }

        fun animations(modelObj: Obj, entity: Obj): List<AnimFacts> {
            val setup = ip.resolve(modelObj.type, "setupAnim", "(Lnet/minecraft/world/entity/Entity;FFFFF)V")
                ?: ip.superChain(modelObj.type).mapNotNull { ip.classes.find(it) }.flatMap { cf -> cf.methods.map { cf to it } }
                    .firstOrNull { it.second.name == "setupAnim" && it.second.code != null }
            if (setup != null) {
                val params = Desc.params(setup.second.desc)
                val args = params.mapIndexed { i, _ -> if (i == 0) entity else Unk }
                runCatching { ip.run(setup.first, setup.second, modelObj, args, 1) }
            }
            val out = ArrayList<AnimFacts>()
            val seen = HashSet<Obj>()
            for (c in modelObj.calls) {
                val role = when (c.name) {
                    "animateWalk" -> "walk"
                    "animate" -> (c.args.firstOrNull() as? Obj)?.origin?.name?.removePrefix("field:")?.let { roleOf(it) } ?: "idle"
                    "animateIdle" -> "idle"
                    else -> continue
                }
                val def = c.args.firstOrNull { it is Obj && (it.type.endsWith("/AnimationDefinition") || it.origin?.name == "build") } as? Obj ?: continue
                if (!seen.add(def)) continue
                animation(def, role, "anim" + out.size)?.let { out.add(it) }
            }
            return out
        }

        private fun roleOf(field: String): String {
            val f = field.lowercase()
            return when {
                "walk" in f || "move" in f || "run" in f -> "walk"
                "attack" in f || "bite" in f || "hit" in f -> "attack"
                "swim" in f -> "swim"
                "fly" in f || "flap" in f -> "fly"
                "sit" in f -> "sit"
                "sleep" in f -> "sleep"
                "death" in f || "die" in f -> "death"
                else -> "idle"
            }
        }

        fun animation(def: Obj, role: String, fallbackName: String): AnimFacts? {
            val builder = (if (def.origin?.name == "build") def.originReceiver as? Obj else null) ?: return null
            val len = num(builder.origin?.args?.firstOrNull()) ?: return null
            val looping = calls(builder, "looping").isNotEmpty()
            val channels = ArrayList<ChannelFacts>()
            for (c in calls(builder, "addAnimation")) {
                val bone = (c.args.getOrNull(0) as? StrV)?.v ?: continue
                val ch = c.args.getOrNull(1) as? Obj ?: continue
                val target = (ch.args.firstOrNull() as? StaticV)?.name?.lowercase() ?: continue
                val frames = (ch.args.getOrNull(1) as? ArrV)?.items ?: continue
                val keys = frames.mapNotNull { k ->
                    val kf = k as? Obj ?: return@mapNotNull null
                    val t = num(kf.args.getOrNull(0)) ?: return@mapNotNull null
                    val vec = kf.args.getOrNull(1) as? Obj ?: return@mapNotNull null
                    val g = (vec.origin?.args ?: vec.args).map { it.num() ?: 0.0 }
                    val interp = (kf.args.getOrNull(2) as? StaticV)?.name?.lowercase() ?: "linear"
                    // degreeVec/posVec/scaleVec: i valori scritti dall'autore (gradi; posizione con l'asse Y come in Blockbench).
                    KeyFacts(t, g.getOrElse(0) { 0.0 }, g.getOrElse(1) { 0.0 }, g.getOrElse(2) { 0.0 }, interp)
                }
                if (keys.isNotEmpty()) channels.add(ChannelFacts(bone, target, keys))
            }
            if (channels.isEmpty()) return null
            return AnimFacts(fallbackName, len, looping, channels, role)
        }
    }
}
