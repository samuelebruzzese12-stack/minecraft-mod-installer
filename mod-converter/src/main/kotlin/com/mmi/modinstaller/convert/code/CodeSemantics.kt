package com.mmi.modinstaller.convert.code

import com.mmi.modinstaller.bytecode.ArrV
import com.mmi.modinstaller.bytecode.Call
import com.mmi.modinstaller.bytecode.Interp
import com.mmi.modinstaller.bytecode.LamV
import com.mmi.modinstaller.bytecode.Obj
import com.mmi.modinstaller.bytecode.Op
import com.mmi.modinstaller.bytecode.Ref
import com.mmi.modinstaller.bytecode.Semantics
import com.mmi.modinstaller.bytecode.StaticV
import com.mmi.modinstaller.bytecode.StrV
import com.mmi.modinstaller.bytecode.Unk
import com.mmi.modinstaller.bytecode.V
import com.mmi.modinstaller.bytecode.VoidV
import com.mmi.modinstaller.convert.Rl
import java.util.IdentityHashMap

/** Una registrazione trovata nel codice: registro («item», «block», «entity_type»…), identificatore, valore costruito. */
class CodeRegistration(val registry: String, val id: Rl, val value: V, val where: String)

/** Un DeferredRegister (Forge/NeoForge/Architectury): registro e mod. */
internal class DReg(val registry: String, val modId: String)

/** Un oggetto «maniglia» restituito da una registrazione (RegistryObject, DeferredHolder, Holder…). */
internal class RegHandle(val reg: CodeRegistration)

/** Un «consumer» finto passato al codice della mod: ogni accept(id, valore) è una registrazione nel registro dato. */
internal class SinkConsumer(val registry: String)

/** Una chiave di registro (ResourceKey.createRegistryKey) o una chiave di voce (ResourceKey.create). */
internal class RegistryKeyRef(val registry: String)
internal class EntryKeyRef(val registry: String?, val rl: Rl)
internal class TagRef(val registry: String?, val rl: Rl)

/** Un «builder» di Registrate (Create e altre): tipo, nome, fabbrica, proprietà da applicare. */
internal class RegBuilder(val kind: String, val name: String, val factory: V, val modId: String?, val parent: Obj?) {
    val propsLambdas = ArrayList<V>()
    var initialProps: V? = null
    var child: Obj? = null
    var registered: CodeRegistration? = null
    var extra: V? = null
}

/** Un pezzo di modello Java (PartDefinition): nome, cubi, posa, figli. */
class PartData(val name: String, val cubes: V?, val pose: V?, val parent: Obj?) {
    val children = ArrayList<Obj>()
}

/**
 * La conoscenza di Minecraft e dei caricatori per l'interprete: riconosce gli identificatori, i registri e le registrazioni
 * (Registry.register, DeferredRegister di Forge/NeoForge/Architectury), gli attributi delle creature, i renderer e i modelli.
 */
internal class CodeSemantics : Semantics {

    val registrations = ArrayList<CodeRegistration>()
    val byValue = IdentityHashMap<Obj, CodeRegistration>()
    /** Tipo di entità (valore) → AttributeSupplier (o il suo builder). */
    val attributes = ArrayList<Pair<V, V>>()
    /** Tipo di entità (valore) → fornitore del renderer. */
    val renderers = ArrayList<Pair<V, V>>()
    /** Strato del modello (ModelLayerLocation) → fornitore della LayerDefinition. */
    val layers = ArrayList<Pair<V, V>>()
    /** Tipo di blocco-entità → fornitore del renderer. */
    val blockEntityRenderers = ArrayList<Pair<V, V>>()
    /** Strato di disegno dei blocchi (blocco → «cutout»/«translucent»…). */
    val renderLayers = ArrayList<Pair<V, String>>()
    /** Comparsa naturale delle creature (Fabric BiomeModifications.addSpawn): entità, categoria, peso, minimo, massimo, biomi. */
    val spawns = ArrayList<List<V>>()
    /** Combustibili registrati nel codice (oggetto, tick). */
    val fuels = ArrayList<Pair<V, V>>()
    /** Oggetti compostabili (oggetto, probabilità). */
    val compostables = ArrayList<Pair<V, V>>()

    // ------------------------------------------------------------------------------------------------------------ valori

    fun rl(v: V?): Rl? = when (v) {
        is Obj -> (v.ext as? Rl) ?: (v.ext as? EntryKeyRef)?.rl ?: (v.ext as? RegHandle)?.reg?.id
        is StrV -> Rl.parse(v.v)
        else -> null
    }

    /** Nome del registro da un valore come BuiltInRegistries.ITEM, Registries.ITEM, ForgeRegistries.ITEMS. */
    fun registryName(v: V?): String? = when (v) {
        is StaticV -> when {
            v.owner == "net/minecraft/core/registries/BuiltInRegistries" || v.owner == "net/minecraft/core/registries/Registries" ||
                v.owner == "net/minecraft/core/Registry" -> v.name.lowercase()
            v.owner.endsWith("/ForgeRegistries") || v.owner.endsWith("/ForgeRegistries\$Keys") || v.owner.endsWith("/NeoForgeRegistries") ||
                v.owner.endsWith("/NeoForgeRegistries\$Keys") -> forgeName(v.name)
            else -> null
        }
        is Obj -> (v.ext as? RegistryKeyRef)?.registry
        else -> null
    }

    private fun forgeName(n: String): String {
        val l = n.lowercase()
        return when (l) {
            "menu_types", "containers" -> "menu"
            "poi_types" -> "point_of_interest_type"
            "tile_entities", "block_entities" -> "block_entity_type"
            "entities" -> "entity_type"
            else -> if (l.endsWith("s")) l.dropLast(1) else l
        }
    }

    fun record(ip: Interp, registry: String, id: Rl, value: V): CodeRegistration {
        val where = ip.callStack.lastOrNull()?.let { it.first.name.substringAfterLast('/') + "." + it.second.name } ?: "?"
        val r = CodeRegistration(registry, id, value, where)
        registrations.add(r)
        if (value is Obj && !byValue.containsKey(value)) byValue[value] = r
        return r
    }

    private fun handle(type: String, reg: CodeRegistration): Obj = Obj(type).also { it.initialized = true; it.ext = RegHandle(reg) }

    private fun obj(type: String, ext: Any?): Obj = Obj(type).also { it.initialized = true; it.ext = ext }

    private fun returnType(ref: Ref): String = ref.desc.substring(ref.desc.indexOf(')') + 2).removeSuffix(";").ifEmpty { "java/lang/Object" }

    // ------------------------------------------------------------------------------------------------------------ costruttori

    override fun construct(ip: Interp, obj: Obj, ref: Ref, args: List<V>, depth: Int): Boolean {
        val external = !ref.owner.startsWith("net/minecraft/") && !ref.owner.startsWith("java/") && ip.classes.find(ref.owner) == null
        if (external && obj.ext == null) {
            val reg = args.firstNotNullOfOrNull { registryName(it) }
            val modId = (args.firstOrNull { it is StrV } as? StrV)?.v
            if (reg != null && modId != null && Rl.parse("$modId:x") != null) {
                obj.ext = DReg(reg, modId)
                if (!obj.initialized) obj.args = args
                if (obj.superCall == null) obj.superCall = Call(ref.owner, "<init>", ref.desc, args)
                return true
            }
        }
        if (ref.owner == RL && obj.type == RL) {
            obj.ext = when (args.size) {
                2 -> { val ns = (args[0] as? StrV)?.v; val p = (args[1] as? StrV)?.v; if (ns != null && p != null) Rl.parse("$ns:$p") else null }
                1 -> rl(args[0])
                else -> null
            }
            obj.args = args
            return true
        }
        return false
    }

    // ------------------------------------------------------------------------------------------------------------ chiamate

    override fun invoke(ip: Interp, op: Int, ref: Ref, receiver: V?, args: List<V>, depth: Int): V? {
        val owner = ref.owner
        val name = ref.name

        // ---- identificatori
        if (owner == RL) {
            when (name) {
                "fromNamespaceAndPath", "tryBuild", "of" -> if (args.size == 2) {
                    val ns = (args[0] as? StrV)?.v; val p = (args[1] as? StrV)?.v
                    return obj(RL, if (ns != null && p != null) Rl.parse("$ns:$p") else null)
                }
                "parse", "tryParse", "bySeparator" -> return obj(RL, rl(args.firstOrNull()))
                "withDefaultNamespace" -> return obj(RL, (args.firstOrNull() as? StrV)?.v?.let { Rl.parse("minecraft:$it") })
                "withPath" -> { val base = rl(receiver); val p = (args.firstOrNull() as? StrV)?.v; return obj(RL, if (base != null && p != null) Rl(base.ns, p) else null) }
                "withPrefix" -> { val base = rl(receiver); val p = (args.firstOrNull() as? StrV)?.v; return obj(RL, if (base != null && p != null) Rl(base.ns, p + base.path) else null) }
                "withSuffix" -> { val base = rl(receiver); val p = (args.firstOrNull() as? StrV)?.v; return obj(RL, if (base != null && p != null) Rl(base.ns, base.path + p) else null) }
                "getPath" -> return rl(receiver)?.let { StrV(it.path) } ?: Unk
                "getNamespace" -> return rl(receiver)?.let { StrV(it.ns) } ?: Unk
                "toString" -> return rl(receiver)?.let { StrV(it.toString()) } ?: Unk
            }
        }
        if (owner == "net/minecraft/resources/ResourceKey") {
            when (name) {
                "createRegistryKey" -> return obj(owner, rl(args.firstOrNull())?.let { RegistryKeyRef(it.path) })
                "create" -> if (args.size == 2) {
                    val id = rl(args[1])
                    return obj(owner, id?.let { EntryKeyRef(registryName(args[0]), it) })
                }
                "location", "identifier" -> return obj(RL, (receiver as? Obj)?.let { (it.ext as? EntryKeyRef)?.rl ?: (it.ext as? RegistryKeyRef)?.let { r -> Rl("minecraft", r.registry) } })
            }
        }
        if (owner == "net/minecraft/tags/TagKey" && name == "create" && args.size == 2) {
            return obj(owner, rl(args[1])?.let { TagRef(registryName(args[0]), it) })
        }
        if (owner.endsWith("/ItemTags") || owner.endsWith("/BlockTags") || owner.endsWith("/EntityTypeTags")) {
            if ((name == "create" || name == "bind" || name == "tag") && args.isNotEmpty()) {
                val reg = when { owner.endsWith("/ItemTags") -> "item"; owner.endsWith("/BlockTags") -> "block"; else -> "entity_type" }
                return obj("net/minecraft/tags/TagKey", (rl(args[0]) ?: (args[0] as? StrV)?.v?.let { Rl.parse(it) })?.let { TagRef(reg, it) })
            }
        }

        // ---- enum di Minecraft: posizione (ordinal) e durata dei pezzi d'armatura
        if (receiver is StaticV) {
            if (name == "ordinal") JavaVanilla.ORDINALS[receiver.owner]?.indexOf(receiver.name)?.takeIf { it >= 0 }?.let { return com.mmi.modinstaller.bytecode.IntV(it) }
            if (name == "getDurability" && (receiver.owner.endsWith("ArmorItem\$Type") || receiver.owner.endsWith("/ArmorType"))) {
                val f = (args.firstOrNull() as? com.mmi.modinstaller.bytecode.IntV)?.v
                val base = JavaVanilla.ARMOR_BASE[receiver.name]
                if (f != null && base != null) return com.mmi.modinstaller.bytecode.IntV(f * base)
            }
            if (name == "getName" || name == "getSerializedName" || name == "name") return StrV(receiver.name.lowercase())
        }

        // ---- Util.make(oggetto, consumer) e simili: il consumer modifica l'oggetto
        if (owner == "net/minecraft/Util" && name == "make" && args.size == 2) {
            val target = args[0]
            val fn = args[1]
            if (fn is LamV) ip.callLambda(fn, listOf(target), depth + 1)
            return target
        }
        if ((owner == "com/google/common/base/Suppliers" && name == "memoize") || (owner == "net/minecraft/util/LazyLoadedValue" && name == "<init>")) {
            return args.firstOrNull() ?: Unk
        }

        // ---- Registry.register / registerForHolder (vanilla, Fabric e chi registra a mano)
        if (owner == "net/minecraft/core/Registry" && (name == "register" || name == "registerForHolder" || name == "registerMapping") && args.size >= 3) {
            val reg = registryName(args[0])
            val id = rl(args[args.size - 2])
            val value = args.last()
            if (reg != null && id != null) {
                val r = record(ip, reg, id, value)
                return if (name == "registerForHolder") handle("net/minecraft/core/Holder\$Reference", r) else value
            }
            return value
        }

        // ---- DeferredRegister (Forge, NeoForge, Architectury)
        if (owner.endsWith("/DeferredRegister") || owner.endsWith("/DeferredRegister\$Items") || owner.endsWith("/DeferredRegister\$Blocks") ||
            owner.endsWith("/DeferredRegister\$DataComponents") || owner.endsWith("/DeferredRegister\$Entities")
        ) {
            when (name) {
                "create", "createOptional" -> {
                    val modId = args.firstOrNull { it is StrV }?.let { (it as StrV).v }
                    val regArg = args.firstOrNull { it !is StrV }
                    val reg = registryName(regArg)
                    return obj(returnType(ref), if (modId != null && reg != null) DReg(reg, modId) else null)
                }
                "createItems" -> return obj(returnType(ref), (args.firstOrNull() as? StrV)?.v?.let { DReg("item", it) })
                "createBlocks" -> return obj(returnType(ref), (args.firstOrNull() as? StrV)?.v?.let { DReg("block", it) })
                "createDataComponents" -> return obj(returnType(ref), (args.lastOrNull() as? StrV)?.v?.let { DReg("data_component_type", it) })
                "createEntities" -> return obj(returnType(ref), (args.firstOrNull() as? StrV)?.v?.let { DReg("entity_type", it) })
            }
            val d = (receiver as? Obj)?.ext as? DReg
            if (d != null) {
                val idName = (args.firstOrNull() as? StrV)?.v
                val id = idName?.let { Rl.parse(d.modId + ":" + it) }
                val ret = returnType(ref)
                when (name) {
                    "register" -> if (id != null && args.size >= 2) {
                        val fn = args[1]
                        val value = if (fn is LamV) {
                            val param = if (fn.ifaceMethod == "apply") listOf<V>(obj(RL, id)) else emptyList()
                            ip.callLambda(fn, param, depth + 1)
                        } else Unk
                        return handle(ret, record(ip, d.registry, id, value))
                    }
                    "registerItem" -> if (id != null && args.size >= 2) {
                        val fn = args[1]
                        val props = if (args.size >= 3) args[2] else newObj(ip, ITEM_PROPS)
                        val value = if (fn is LamV) ip.callLambda(fn, listOf(props), depth + 1) else Unk
                        return handle(ret, record(ip, "item", id, value))
                    }
                    "registerSimpleItem" -> if (id != null) {
                        val props = if (args.size >= 2) args[1] else newObj(ip, ITEM_PROPS)
                        val item = Obj(ITEM).also { it.args = listOf(props); it.initialized = true; it.superCall = Call(ITEM, "<init>", "", listOf(props)) }
                        return handle(ret, record(ip, "item", id, item))
                    }
                    "registerBlock" -> if (id != null && args.size >= 2) {
                        val fn = args[1]
                        val props = if (args.size >= 3) args[2] else newObj(ip, BLOCK_PROPS)
                        val value = if (fn is LamV) ip.callLambda(fn, listOf(props), depth + 1) else Unk
                        return handle(ret, record(ip, "block", id, value))
                    }
                    "registerSimpleBlock" -> if (id != null) {
                        val props = if (args.size >= 2) args[1] else newObj(ip, BLOCK_PROPS)
                        val block = Obj(BLOCK).also { it.args = listOf(props); it.initialized = true; it.superCall = Call(BLOCK, "<init>", "", listOf(props)) }
                        return handle(ret, record(ip, "block", id, block))
                    }
                    "registerSimpleBlockItem" -> {
                        // (nome, blocco, proprietà) oppure (maniglia del blocco[, proprietà])
                        val blockArg = if (args.firstOrNull() is StrV) args.getOrNull(1) else args.firstOrNull()
                        val blockReg = ((blockArg as? Obj)?.ext as? RegHandle)?.reg
                        val itemId = id ?: blockReg?.id
                        if (itemId != null) {
                            val block = blockReg?.value ?: (blockArg?.let { b -> if (b is LamV) ip.callLambda(b, emptyList(), depth + 1) else b } ?: Unk)
                            val props = args.lastOrNull { it is Obj && (it.type == ITEM_PROPS) } ?: newObj(ip, ITEM_PROPS)
                            val item = Obj(BLOCK_ITEM).also { it.args = listOf(block, props); it.initialized = true; it.superCall = Call(BLOCK_ITEM, "<init>", "", listOf(block, props)) }
                            return handle(ret, record(ip, "item", itemId, item))
                        }
                    }
                }
            }
        }

        // ---- bus degli eventi di Forge/NeoForge: i metodi iscritti si leggono subito (con un evento simbolico)
        if ((owner.endsWith("/IEventBus") || owner.endsWith("/EventBus")) && (name == "addListener" || name == "register")) {
            for (a in args) {
                when (a) {
                    is LamV -> {
                        val params = com.mmi.modinstaller.bytecode.Desc.params(a.impl.desc)
                        val evType = params.lastOrNull()?.let { com.mmi.modinstaller.bytecode.Desc.objectType(it) } ?: "java/lang/Object"
                        if (subscribed.add(a.impl.owner + "." + a.impl.name + a.impl.desc)) {
                            ip.callLambda(a, listOf(Obj(evType).also { it.initialized = true }), depth + 1)
                        }
                    }
                    is Obj -> runSubscribers(ip, a.type, a, depth)
                    is com.mmi.modinstaller.bytecode.ClassV -> runSubscribers(ip, a.name, null, depth)
                    else -> {}
                }
            }
            return VoidV
        }
        if (name == "enqueueWork" && args.size == 1 && args[0] is LamV) {
            ip.callLambda(args[0] as LamV, emptyList(), depth + 1)
            return Obj("java/util/concurrent/CompletableFuture").also { it.initialized = true }
        }

        // ---- owo-lib (Fabric): FieldRegistrationHandler registra i campi statici di una classe «contenitore»
        if (owner == "io/wispforest/owo/registration/reflect/FieldRegistrationHandler" && (name == "register" || name == "processSimple")) {
            val c = (args.firstOrNull() as? com.mmi.modinstaller.bytecode.ClassV)?.name ?: return VoidV
            val ns = (args.getOrNull(1) as? StrV)?.v
            val recurse = (args.lastOrNull() as? com.mmi.modinstaller.bytecode.IntV)?.v == 1
            owoContainer(ip, c, if (name == "register") ns else null, recurse, depth)
            return VoidV
        }

        // ---- Registrate (Create e altre): REGISTRATE.item/block/entity(...)…register()
        registrate(ip, ref, receiver, args, depth)?.let { return it }

        // ---- involucri di registro di librerie (ResourcefulLib, Architectury Registrar, Moonlight…): create(registro, mod)
        if (!owner.startsWith("net/minecraft/") && !owner.startsWith("java/") && ip.classes.find(owner) == null) {
            generic(ip, ref, receiver, args, depth)?.let { return it }
        }

        // ---- consumer finto: accept(id, valore) / accept(valore, id)
        ((receiver as? Obj)?.ext as? SinkConsumer)?.let { sink ->
            if (name == "accept" && args.size == 2) {
                val a = args[0]; val b = args[1]
                val id = rl(a) ?: rl(b)
                val value = if (rl(a) != null) b else a
                if (id != null) record(ip, sink.registry, id, value)
            }
            return VoidV
        }

        // ---- RegisterEvent (Forge/NeoForge): event.register(Registries.BLOCK, helper -> …) e helper.register(id, valore)
        if (owner.endsWith("/RegisterEvent") && name == "register" && args.size >= 2) {
            val reg = registryName(args[0])
            if (reg != null) {
                val fn = args.last()
                if (args.size == 2 && fn is LamV) {
                    val helper = obj("net/neoforged/neoforge/registries/RegisterEvent\$RegisterHelper", DReg(reg, ""))
                    ip.callLambda(fn, listOf(helper), depth + 1)
                } else if (args.size == 3) {
                    val id = rl(args[1])
                    val value = if (fn is LamV) ip.callLambda(fn, emptyList(), depth + 1) else fn
                    if (id != null) record(ip, reg, id, value)
                }
            }
            return VoidV
        }
        if (owner.endsWith("/RegisterEvent\$RegisterHelper") && name == "register" && args.size == 2) {
            val d = (receiver as? Obj)?.ext as? DReg
            val id = rl(args[0])
            if (d != null && id != null) record(ip, d.registry, id, args[1])
            return VoidV
        }

        // ---- maniglie: get(), value(), getId()…
        val h = (receiver as? Obj)?.ext as? RegHandle
        if (h != null) {
            when (name) {
                "get", "value", "asItem", "comp_349", "unwrap" -> return h.reg.value
                "getId", "getKey", "location", "unwrapKey", "key", "getRegisteredName" -> return obj(RL, h.reg.id)
            }
        }

        // ---- attributi delle creature
        if ((owner.endsWith("/EntityAttributeCreationEvent") && name == "put" && args.size == 2) ||
            (owner.endsWith("/FabricDefaultAttributeRegistry") && name == "register" && args.size == 2)
        ) {
            attributes.add(resolve(ip, args[0], depth) to args[1])
            return VoidV
        }

        // ---- renderer delle creature e dei blocchi-entità, strati dei modelli
        if ((owner == "net/minecraft/client/renderer/entity/EntityRenderers" && name == "register" && args.size == 2) ||
            (owner.endsWith("/EntityRendererRegistry") && name == "register" && args.size == 2) ||
            (owner.endsWith("/EntityRenderersEvent\$RegisterRenderers") && name == "registerEntityRenderer" && args.size == 2)
        ) {
            renderers.add(resolve(ip, args[0], depth) to args[1])
            return VoidV
        }
        if ((owner == "net/minecraft/client/renderer/blockentity/BlockEntityRenderers" && name == "register" && args.size == 2) ||
            (owner.endsWith("/BlockEntityRendererRegistry") && name == "register" && args.size == 2) ||
            (owner.endsWith("/EntityRenderersEvent\$RegisterRenderers") && name == "registerBlockEntityRenderer" && args.size == 2)
        ) {
            blockEntityRenderers.add(resolve(ip, args[0], depth) to args[1])
            return VoidV
        }
        if ((owner.endsWith("/EntityRenderersEvent\$RegisterLayerDefinitions") && name == "registerLayerDefinition" && args.size == 2) ||
            (owner.endsWith("/EntityModelLayerRegistry") && name == "registerModelLayer" && args.size == 2)
        ) {
            layers.add(args[0] to args[1])
            return VoidV
        }
        if ((owner.endsWith("/ItemBlockRenderTypes") && name == "setRenderLayer" && args.size == 2) ||
            (owner.endsWith("/BlockRenderLayerMap") && (name == "putBlock" || name == "putBlocks") && args.size >= 2)
        ) {
            val layer = renderLayerName(args.last())
            if (layer != null) {
                val first = args[args.size - 2]
                if (first is ArrV) first.items.forEach { renderLayers.add(resolve(ip, it, depth) to layer) } else renderLayers.add(resolve(ip, first, depth) to layer)
            }
            return VoidV
        }
        if (owner.endsWith("/BiomeModifications") && name == "addSpawn" && args.size >= 6) {
            spawns.add(listOf(resolve(ip, args[2], depth), args[1], args[3], args[4], args[5], args[0]))
            return VoidV
        }
        if (owner.endsWith("/FuelRegistry") && name == "add" && args.size == 2) { fuels.add(resolve(ip, args[0], depth) to args[1]); return VoidV }
        if (owner.endsWith("/CompostingChanceRegistry") && name == "add" && args.size == 2) { compostables.add(resolve(ip, args[0], depth) to args[1]); return VoidV }

        // ---- modelli Java (MeshDefinition / PartDefinition)
        if (owner == "net/minecraft/client/model/geom/builders/MeshDefinition" && name == "getRoot" && receiver is Obj) {
            return receiver.fields.getOrPut("root") { obj(PART_DEF, PartData("root", null, null, null)) }
        }
        if (owner == PART_DEF && (name == "addOrReplaceChild" || name == "addChild") && receiver is Obj && args.size >= 3) {
            val parent = receiver.ext as? PartData
            val childName = (args[0] as? StrV)?.v ?: "part" + (parent?.children?.size ?: 0)
            val cubes = args[1].let { if (it is Obj && it.type == CUBE_LIST_BUILDER) it else resolveCubes(it) }
            val child = obj(PART_DEF, PartData(childName, cubes, args[2], receiver))
            parent?.children?.removeAll { (it.ext as? PartData)?.name == childName }
            parent?.children?.add(child)
            return child
        }
        if (owner == PART_DEF && name == "getChild" && receiver is Obj) {
            val n = (args.firstOrNull() as? StrV)?.v
            return (receiver.ext as? PartData)?.children?.firstOrNull { (it.ext as? PartData)?.name == n } ?: Unk
        }
        return null
    }

    private fun resolveCubes(v: V): V = v

    /** Metodi già letti come iscritti a un evento (per non leggerli due volte). */
    val subscribed = HashSet<String>()

    /** I metodi con @SubscribeEvent di una classe registrata sul bus. */
    fun runSubscribers(ip: Interp, cls: String, instance: Obj?, depth: Int) {
        val cf = ip.classes.find(cls) ?: return
        for (m in cf.methods) {
            if (m.code == null || m.annotations.none { it.endsWith("/SubscribeEvent;") }) continue
            if (instance == null && !m.isStatic) continue
            if (!subscribed.add(cls + "." + m.name + m.desc)) continue
            val evType = com.mmi.modinstaller.bytecode.Desc.params(m.desc).firstOrNull()?.let { com.mmi.modinstaller.bytecode.Desc.objectType(it) } ?: "java/lang/Object"
            try {
                ip.run(cf, m, if (m.isStatic) null else instance, listOf(Obj(evType).also { it.initialized = true }), depth + 1)
            } catch (e: com.mmi.modinstaller.bytecode.Budget.Exhausted) {
                throw e
            } catch (e: RuntimeException) {
                ip.problems.add("${cls.substringAfterLast('/')}.${m.name}: ${e.javaClass.simpleName}")
            }
        }
    }

    // ------------------------------------------------------------------------------------------------------------ owo-lib

    private fun owoContainer(ip: Interp, cls: String, ns: String?, recurse: Boolean, depth: Int) {
        val cf = ip.classes.find(cls) ?: return
        ip.ensureInit(cls, depth)
        val instance = Obj(cls)
        ip.construct(instance, Ref(cls, "<init>", "()V"), emptyList(), depth + 1)
        val ifaces = HashSet<String>()
        for (c in ip.superChain(cls)) ip.classes.find(c)?.interfaces?.let { ifaces.addAll(it) }
        val registry: String? = when {
            ifaces.any { it.endsWith("/ItemRegistryContainer") } -> "item"
            ifaces.any { it.endsWith("/BlockRegistryContainer") } -> "block"
            else -> ip.resolve(cls, "getRegistry", "()Lnet/minecraft/core/Registry;")?.let { (c, m) -> registryName(ip.run(c, m, instance, emptyList(), depth + 1)) }
        }
        val target = ip.resolve(cls, "getTargetFieldType", "()Ljava/lang/Class;")?.let { (c, m) -> (ip.run(c, m, instance, emptyList(), depth + 1) as? com.mmi.modinstaller.bytecode.ClassV)?.name }
        val simple = ns == null
        for (f in cf.fields) {
            if (!f.isStatic || f.access and 0x1000 != 0) continue
            val value = ip.statics["$cls.${f.name}"] ?: continue
            if (value !is Obj) continue
            val fieldType = com.mmi.modinstaller.bytecode.Desc.objectType(f.desc)
            if (target != null && fieldType != target && ip.superChain(value.type).none { it == target } && value.type != target) continue
            val identifier = f.name.lowercase()
            if (simple) {
                val m = cf.methods.firstOrNull { it.name == "processField" && it.code != null && !it.isStatic }
                    ?: ip.superChain(cls).drop(1).mapNotNull { ip.classes.find(it) }.flatMap { it.methods }.firstOrNull { it.name == "processField" && it.code != null }
                if (m != null) ip.run(cf, m, instance, listOf(value, StrV(identifier), Unk), depth + 1)
                continue
            }
            val id = Rl.parse("$ns:$identifier") ?: continue
            if (registry != null) {
                val r = record(ip, registry, id, value)
                if (registry == "block" && f.name != "") {
                    val props = Obj(ITEM_PROPS).also { it.initialized = true }
                    val item = Obj(BLOCK_ITEM).also { it.args = listOf(value, props); it.initialized = true; it.superCall = Call(BLOCK_ITEM, "<init>", "", listOf(value, props)) }
                    record(ip, "item", r.id, item)
                }
            }
            val post = cf.methods.firstOrNull { it.name == "postProcessField" && it.code != null && !it.isStatic }
            if (post != null) ip.run(cf, post, instance, listOf(StrV(ns), value, StrV(identifier), Unk), depth + 1)
        }
        if (recurse) {
            val prefix = "$cls$"
            for (inner in (ip.classes as? JarClassSource)?.classNames.orEmpty()) {
                if (inner.startsWith(prefix) && !inner.substring(prefix.length).contains('$')) owoContainer(ip, inner, ns, false, depth + 1)
            }
        }
    }

    // ------------------------------------------------------------------------------------------------------------ Registrate

    private fun isRegistrate(ip: Interp, owner: String): Boolean =
        owner == "com/tterrag/registrate/AbstractRegistrate" || owner == "com/tterrag/registrate/Registrate" ||
            ip.superChain(owner).any { it == "com/tterrag/registrate/AbstractRegistrate" || it == "com/tterrag/registrate/Registrate" }

    private fun registrateModId(receiver: V?): String? {
        val o = receiver as? Obj ?: return null
        (o.superCall?.args?.firstOrNull() as? StrV)?.let { return it.v }
        (o.args.firstOrNull() as? StrV)?.let { return it.v }
        (o.origin?.args?.firstOrNull() as? StrV)?.let { return it.v }
        return null
    }

    private fun registrate(ip: Interp, ref: Ref, receiver: V?, args: List<V>, depth: Int): V? {
        val name = ref.name
        val b = (receiver as? Obj)?.ext as? RegBuilder
        if (b == null) {
            if (receiver == null || !isRegistrate(ip, ref.owner)) return null
            val kind = when (name) {
                "item" -> "item"; "block" -> "block"; "entity" -> "entity_type"; "blockEntity" -> "block_entity_type"
                "fluid", "virtualFluid", "standardFluid" -> "fluid"; "menu" -> "menu"; "enchantment" -> "enchantment"
                "effect" -> "mob_effect"; "generic", "simple" -> "generic"
                else -> return null
            }
            // (nome, fabbrica[, categoria…]) o (genitore, nome, fabbrica…): il nome è il primo testo.
            val n = args.firstOrNull { it is StrV } as? StrV ?: return null
            val after = args.drop(args.indexOf(n) + 1)
            val factory = after.firstOrNull { it is LamV } ?: Unk
            val builder = obj(ref.desc.substringAfterLast(')').removePrefix("L").removeSuffix(";"), RegBuilder(kind, n.v, factory, registrateModId(receiver), null))
            (builder.ext as RegBuilder).extra = after.firstOrNull { it is StaticV }
            return builder
        }
        when (name) {
            "properties" -> { args.firstOrNull()?.let { b.propsLambdas.add(it) }; return receiver }
            "initialProperties" -> { b.initialProps = args.firstOrNull(); return receiver }
            "transform" -> {
                val fn = args.firstOrNull() as? LamV ?: return receiver
                val r = ip.callLambda(fn, listOf(receiver!!), depth + 1)
                return if (r is Obj && r.ext is RegBuilder) r else receiver
            }
            "item" -> {
                val factory = args.firstOrNull { it is LamV } ?: Unk
                val child = obj("com/tterrag/registrate/builders/ItemBuilder", RegBuilder("item", b.name, factory, b.modId, receiver as Obj))
                b.child = child
                return child
            }
            "simpleItem" -> {
                b.child = obj("com/tterrag/registrate/builders/ItemBuilder", RegBuilder("item", b.name, Unk, b.modId, receiver as Obj))
                return receiver
            }
            "build" -> return b.parent ?: receiver
            "register" -> {
                val reg = registerBuilder(ip, b, depth)
                val ret = ref.desc.substringAfterLast(')').removePrefix("L").removeSuffix(";")
                return if (reg != null) handle(ret, reg) else Unk
            }
        }
        // Tutto il resto (lingua, modelli, ricette, tag…) restituisce il builder stesso.
        val ret = ref.desc.substringAfterLast(')')
        return if (ret.startsWith("Lcom/tterrag/registrate/builders/")) receiver else if (ret == "V") VoidV else null
    }

    private fun registerBuilder(ip: Interp, b: RegBuilder, depth: Int): CodeRegistration? {
        b.registered?.let { return it }
        val modId = b.modId ?: b.parent?.let { ((it.ext as? RegBuilder)?.modId) } ?: return null
        val id = Rl.parse("$modId:${b.name}") ?: return null
        val value: V = when (b.kind) {
            "block" -> {
                var props: V = Obj(BLOCK_PROPS).also { it.initialized = true }
                b.initialProps?.let { init ->
                    val base = if (init is LamV) ip.callLambda(init, emptyList(), depth + 1) else init
                    props = Obj(BLOCK_PROPS).also { it.initialized = true; it.origin = Call(BLOCK_PROPS, "ofFullCopy", "", listOf(base)) }
                }
                for (fn in b.propsLambdas) if (fn is LamV) ip.callLambda(fn, listOf(props), depth + 1).let { if (it is Obj) props = it }
                val f = b.factory
                if (f is LamV) ip.callLambda(f, listOf(props), depth + 1) else Obj(BLOCK).also { it.args = listOf(props); it.initialized = true }
            }
            "item" -> {
                var props: V = Obj(ITEM_PROPS).also { it.initialized = true }
                for (fn in b.propsLambdas) if (fn is LamV) ip.callLambda(fn, listOf(props), depth + 1).let { if (it is Obj) props = it }
                val parentReg = (b.parent?.ext as? RegBuilder)?.let { registerBuilder(ip, it, depth) }
                val f = b.factory
                when {
                    f is LamV && parentReg != null -> ip.callLambda(f, listOf(parentReg.value, props), depth + 1)
                    f is LamV -> ip.callLambda(f, listOf(props), depth + 1)
                    parentReg != null -> Obj(BLOCK_ITEM).also { it.args = listOf(parentReg.value, props); it.initialized = true; it.superCall = Call(BLOCK_ITEM, "<init>", "", listOf(parentReg.value, props)) }
                    else -> Obj(ITEM).also { it.args = listOf(props); it.initialized = true }
                }
            }
            "entity_type" -> {
                val builder = Obj("net/minecraft/world/entity/EntityType\$Builder").also {
                    it.initialized = true
                    it.origin = Call("net/minecraft/world/entity/EntityType\$Builder", "of", "", listOf(b.factory, b.extra ?: Unk))
                }
                for (fn in b.propsLambdas) if (fn is LamV) ip.callLambda(fn, listOf(builder), depth + 1)
                Obj("net/minecraft/world/entity/EntityType").also { it.initialized = true; it.origin = Call("net/minecraft/world/entity/EntityType\$Builder", "build", "", listOf(StrV(b.name))); it.originReceiver = builder }
            }
            else -> (b.factory as? LamV)?.let { ip.callLambda(it, emptyList(), depth + 1) } ?: Unk
        }
        val registry = if (b.kind == "generic") return null else b.kind
        val r = record(ip, registry, id, value)
        b.registered = r
        // Il blocco con il suo oggetto: si registra anche l'oggetto.
        b.child?.let { c -> (c.ext as? RegBuilder)?.let { registerBuilder(ip, it, depth) } }
        return r
    }

    // ------------------------------------------------------------------------------------------------------------ librerie

    private fun hasRegistryArg(args: List<V>): String? = args.firstNotNullOfOrNull { registryName(it) }

    private fun registryFromName(n: String): String? {
        val l = n.lowercase()
        return when {
            l.contains("blockentity") || l.contains("block_entity") || l.contains("tileentity") -> "block_entity_type"
            l.contains("blockitem") -> "item"
            l.contains("item") -> "item"
            l.contains("block") -> "block"
            l.contains("entity") -> "entity_type"
            l.contains("sound") -> "sound_event"
            l.contains("particle") -> "particle_type"
            l.contains("effect") -> "mob_effect"
            l.contains("menu") || l.contains("container") -> "menu"
            else -> null
        }
    }

    private fun originRegistry(v: V?, depth: Int = 0): String? {
        val o = v as? Obj ?: return null
        (o.ext as? DReg)?.let { return it.registry }
        if (depth > 3) return null
        o.origin?.args?.let { a -> hasRegistryArg(a)?.let { return it } }
        return originRegistry(o.originReceiver, depth + 1)
    }

    /** Regole generali per le librerie di registrazione che l'app non conosce per nome. */
    private fun generic(ip: Interp, ref: Ref, receiver: V?, args: List<V>, depth: Int): V? {
        val n = ref.name
        val lam = args.firstOrNull { it is LamV } as? LamV
        val d = (receiver as? Obj)?.ext as? DReg
        // H2: registrazione su un involucro già riconosciuto: (nome, fornitore) o (identificatore, fornitore)
        if (d != null && n.startsWith("register") && lam != null) {
            val first = args.first()
            val id = (first as? StrV)?.v?.let { Rl.parse(d.modId + ":" + it) } ?: rl(first)
            if (id != null) {
                val value = ip.callLambda(lam, if (lam.ifaceMethod == "apply") listOf<V>(obj(RL, id)) else emptyList(), depth + 1)
                return handle(ref.desc.substringAfterLast(')').removePrefix("L").removeSuffix(";").ifEmpty { "java/lang/Object" }, record(ip, d.registry, id, value))
            }
        }
        // H5: (registro, funzione che riceve un «consumer» di registrazione) — es. GlitchCore addRegistrar(BLOCK, ModBlocks::setup)
        val regArg = hasRegistryArg(args)
        if (regArg != null && lam != null && args.none { it is StrV }) {
            val p = com.mmi.modinstaller.bytecode.Desc.params(lam.impl.desc).drop(maxOf(0, lam.captured.size - (if (lam.impl.kind == com.mmi.modinstaller.bytecode.Handle.INVOKE_STATIC) 0 else 1)))
            if (p.size == 1 && (p[0].contains("Consumer") || p[0].contains("Registrar") || p[0].contains("Function"))) {
                val sink = obj("java/util/function/BiConsumer", SinkConsumer(regArg))
                ip.callLambda(lam, listOf(sink), depth + 1)
                return VoidV
            }
        }
        // H1: creazione di un involucro: un registro e il nome della mod
        val reg = hasRegistryArg(args)
        val modId = args.firstOrNull { it is StrV } as? StrV
        if (reg != null && modId != null && lam == null && Rl.parse(modId.v + ":x") != null && Desc.objectType(ref) != null) {
            return obj(Desc.objectType(ref)!!, DReg(reg, modId.v))
        }
        // H3/H4: registrazione diretta: (identificatore, fornitore) con il registro negli argomenti, nel nome o nell'origine
        if (n.startsWith("register") && lam != null) {
            val idArg = args.firstOrNull { rl(it) != null && it !is LamV }
            val id = idArg?.let { rl(it) }
            val registry = reg ?: originRegistry(receiver) ?: registryFromName(n)
            if (id != null && registry != null && id.ns != "minecraft") {
                val value = ip.callLambda(lam, emptyList(), depth + 1)
                return handle(Desc.objectType(ref) ?: "java/lang/Object", record(ip, registry, id, value))
            }
        }
        return null
    }

    private object Desc {
        fun objectType(ref: Ref): String? = com.mmi.modinstaller.bytecode.Desc.objectType(com.mmi.modinstaller.bytecode.Desc.returnType(ref.desc))
    }

    /** Se [v] è una maniglia di registrazione, il valore registrato; altrimenti [v]. Le lambda «fornitori» si chiamano. */
    fun resolve(ip: Interp, v: V, depth: Int): V {
        if (v is Obj) (v.ext as? RegHandle)?.let { return it.reg.value }
        if (v is LamV && (v.ifaceMethod == "get")) return ip.callLambda(v, emptyList(), depth + 1)
        return v
    }

    private fun renderLayerName(v: V): String? {
        val o = (v as? Obj)?.origin ?: return (v as? StaticV)?.name?.lowercase()
        return when (o.name) {
            "cutout", "getCutout" -> "cutout"
            "cutoutMipped", "getCutoutMipped" -> "cutout"
            "translucent", "getTranslucent" -> "translucent"
            "solid", "getSolid" -> "solid"
            else -> null
        }
    }

    private fun newObj(ip: Interp, type: String): Obj = Obj(type).also { it.initialized = true }

    companion object {
        const val RL = "net/minecraft/resources/ResourceLocation"
        const val ITEM = "net/minecraft/world/item/Item"
        const val ITEM_PROPS = "net/minecraft/world/item/Item\$Properties"
        const val BLOCK = "net/minecraft/world/level/block/Block"
        const val BLOCK_ITEM = "net/minecraft/world/item/BlockItem"
        const val BLOCK_PROPS = "net/minecraft/world/level/block/state/BlockBehaviour\$Properties"
        const val PART_DEF = "net/minecraft/client/model/geom/builders/PartDefinition"
        const val CUBE_LIST_BUILDER = "net/minecraft/client/model/geom/builders/CubeListBuilder"

        /** Classi e metodi che, se citati da una classe della mod, la rendono un punto di partenza dell'analisi. */
        val ENTRY_OWNERS = listOf(
            "net/minecraft/core/Registry", "/DeferredRegister", "/EntityAttributeCreationEvent", "/FabricDefaultAttributeRegistry",
            "net/minecraft/client/renderer/entity/EntityRenderers", "/EntityRendererRegistry", "/EntityRenderersEvent\$Register",
            "net/minecraft/client/renderer/blockentity/BlockEntityRenderers", "/BlockEntityRendererRegistry", "/EntityModelLayerRegistry",
            "/ItemBlockRenderTypes", "/BlockRenderLayerMap", "/BiomeModifications", "/FuelRegistry", "/CompostingChanceRegistry",
            "io/wispforest/owo/registration/reflect/FieldRegistrationHandler", "com/tterrag/registrate/", "/ResourcefulRegistries",
            "/ResourcefulRegistry", "/RegistrarManager", "/Registrar", "/RegHelper", "/RegistryHelper", "/RegistrationProvider", "/RegisterEvent"
        )

        fun isEntryRef(r: Ref): Boolean = ENTRY_OWNERS.any { o ->
            when {
                o.startsWith("/") -> r.owner.endsWith(o) || r.owner.contains(o)
                o.endsWith("/") -> r.owner.startsWith(o)
                else -> r.owner == o
            }
        } || (r.name.startsWith("register") && r.desc.contains("Ljava/util/function/Supplier;") && !r.owner.startsWith("java/"))
    }
}
