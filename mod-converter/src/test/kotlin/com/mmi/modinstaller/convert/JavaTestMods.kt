package com.mmi.modinstaller.convert

import java.io.File
import java.nio.file.Files
import javax.tools.ToolProvider

/**
 * Mod di prova scritte in Java e compilate durante il test contro piccoli «finti» Minecraft e caricatori: così l'analisi del
 * codice si prova su bytecode vero (javac), come quello delle mod pubblicate. I finti Minecraft non entrano nel JAR della mod.
 */
object JavaTestMods {

    private val STUBS_MOJANG = mapOf(
        "net/minecraft/resources/ResourceLocation" to "package net.minecraft.resources; public class ResourceLocation { public static ResourceLocation fromNamespaceAndPath(String a, String b) { return null; } }",
        "net/minecraft/resources/ResourceKey" to "package net.minecraft.resources; public class ResourceKey<T> {}",
        "net/minecraft/core/Holder" to "package net.minecraft.core; public interface Holder<T> { T value(); }",
        "net/minecraft/core/Registry" to "package net.minecraft.core; import net.minecraft.resources.ResourceLocation; public interface Registry<T> { static <V, T extends V> T register(Registry<V> r, ResourceLocation id, T v) { return v; } }",
        "net/minecraft/core/registries/BuiltInRegistries" to "package net.minecraft.core.registries; import net.minecraft.core.Registry; public class BuiltInRegistries { public static Registry ITEM; public static Registry BLOCK; public static Registry ENTITY_TYPE; }",
        "net/minecraft/core/registries/Registries" to "package net.minecraft.core.registries; import net.minecraft.resources.ResourceKey; public class Registries { public static ResourceKey ITEM; public static ResourceKey BLOCK; public static ResourceKey ENTITY_TYPE; }",
        "net/minecraft/world/item/Rarity" to "package net.minecraft.world.item; public enum Rarity { COMMON, UNCOMMON, RARE, EPIC }",
        "net/minecraft/world/item/Item" to """package net.minecraft.world.item; import net.minecraft.world.food.FoodProperties;
            public class Item { public Item(Properties p) {}
              public static class Properties { public Properties stacksTo(int n) { return this; } public Properties durability(int n) { return this; }
                public Properties fireResistant() { return this; } public Properties food(FoodProperties f) { return this; } public Properties rarity(Rarity r) { return this; } } }""",
        "net/minecraft/world/item/Tier" to "package net.minecraft.world.item; public interface Tier { int getUses(); float getSpeed(); float getAttackDamageBonus(); int getLevel(); int getEnchantmentValue(); }",
        "net/minecraft/world/item/Tiers" to """package net.minecraft.world.item; public enum Tiers implements Tier { WOOD, STONE, IRON, DIAMOND, GOLD, NETHERITE;
            public int getUses() { return 0; } public float getSpeed() { return 0; } public float getAttackDamageBonus() { return 0; } public int getLevel() { return 0; } public int getEnchantmentValue() { return 0; } }""",
        "net/minecraft/world/item/TieredItem" to "package net.minecraft.world.item; public class TieredItem extends Item { public TieredItem(Tier t, Properties p) { super(p); } }",
        "net/minecraft/world/item/SwordItem" to "package net.minecraft.world.item; public class SwordItem extends TieredItem { public SwordItem(Tier t, int d, float s, Properties p) { super(t, p); } }",
        "net/minecraft/world/item/BlockItem" to "package net.minecraft.world.item; import net.minecraft.world.level.block.Block; public class BlockItem extends Item { public BlockItem(Block b, Properties p) { super(p); } }",
        "net/minecraft/world/item/ArmorMaterial" to "package net.minecraft.world.item; public interface ArmorMaterial { int getDurabilityForType(ArmorItem.Type t); int getDefenseForType(ArmorItem.Type t); int getEnchantmentValue(); float getToughness(); float getKnockbackResistance(); }",
        "net/minecraft/world/item/ArmorItem" to "package net.minecraft.world.item; public class ArmorItem extends Item { public enum Type { HELMET, CHESTPLATE, LEGGINGS, BOOTS } public ArmorItem(ArmorMaterial m, Type t, Properties p) { super(p); } }",
        "net/minecraft/world/effect/MobEffect" to "package net.minecraft.world.effect; public class MobEffect {}",
        "net/minecraft/world/effect/MobEffects" to "package net.minecraft.world.effect; public class MobEffects { public static MobEffect REGENERATION; public static MobEffect POISON; }",
        "net/minecraft/world/effect/MobEffectInstance" to "package net.minecraft.world.effect; public class MobEffectInstance { public MobEffectInstance(MobEffect e, int d, int a) {} }",
        "net/minecraft/world/food/FoodProperties" to """package net.minecraft.world.food; import net.minecraft.world.effect.MobEffectInstance; public class FoodProperties {
            public static class Builder { public Builder nutrition(int n) { return this; } public Builder saturationModifier(float s) { return this; }
              public Builder effect(MobEffectInstance e, float c) { return this; } public Builder alwaysEdible() { return this; } public Builder fast() { return this; } public FoodProperties build() { return null; } } }""",
        "net/minecraft/world/level/block/state/BlockState" to "package net.minecraft.world.level.block.state; public class BlockState {}",
        "net/minecraft/world/level/block/SoundType" to "package net.minecraft.world.level.block; public class SoundType { public static SoundType METAL; public static SoundType STONE; }",
        "net/minecraft/world/level/block/state/BlockBehaviour" to """package net.minecraft.world.level.block.state; import net.minecraft.world.level.block.SoundType; import java.util.function.ToIntFunction;
            public class BlockBehaviour { public static class Properties { public static Properties of() { return null; } public Properties strength(float h, float r) { return this; }
              public Properties strength(float h) { return this; } public Properties lightLevel(ToIntFunction<BlockState> f) { return this; } public Properties sound(SoundType s) { return this; }
              public Properties requiresCorrectToolForDrops() { return this; } public Properties friction(float f) { return this; } } }""",
        "net/minecraft/world/level/block/Block" to "package net.minecraft.world.level.block; import net.minecraft.world.level.block.state.BlockBehaviour; public class Block extends BlockBehaviour { public Block(BlockBehaviour.Properties p) {} }",
        "net/minecraft/util/valueproviders/IntProvider" to "package net.minecraft.util.valueproviders; public class IntProvider {}",
        "net/minecraft/util/valueproviders/UniformInt" to "package net.minecraft.util.valueproviders; public class UniformInt extends IntProvider { public static UniformInt of(int a, int b) { return null; } }",
        "net/minecraft/world/level/block/DropExperienceBlock" to "package net.minecraft.world.level.block; import net.minecraft.util.valueproviders.IntProvider; public class DropExperienceBlock extends Block { public DropExperienceBlock(IntProvider x, Properties p) { super(p); } }",
        "net/minecraft/world/level/Level" to "package net.minecraft.world.level; public class Level {}",
        "net/minecraft/world/entity/MobCategory" to "package net.minecraft.world.entity; public enum MobCategory { MONSTER, CREATURE, AMBIENT, WATER_CREATURE, MISC }",
        "net/minecraft/world/entity/EntityType" to """package net.minecraft.world.entity; import net.minecraft.world.level.Level; public class EntityType<T extends Entity> {
            public interface EntityFactory<T extends Entity> { T create(EntityType<T> t, Level l); }
            public static class Builder<T extends Entity> { public static <T extends Entity> Builder<T> of(EntityFactory<T> f, MobCategory c) { return null; }
              public Builder<T> sized(float w, float h) { return this; } public EntityType<T> build(String id) { return null; } } }""",
        "net/minecraft/world/entity/AnimationState" to "package net.minecraft.world.entity; public class AnimationState {}",
        "net/minecraft/world/entity/Entity" to "package net.minecraft.world.entity; import net.minecraft.world.level.Level; public class Entity { public Entity(EntityType<?> t, Level l) {} }",
        "net/minecraft/world/entity/LivingEntity" to "package net.minecraft.world.entity; import net.minecraft.world.level.Level; public class LivingEntity extends Entity { public LivingEntity(EntityType<?> t, Level l) { super(t, l); } }",
        "net/minecraft/world/entity/ai/goal/GoalSelector" to "package net.minecraft.world.entity.ai.goal; public class GoalSelector { public void addGoal(int p, Goal g) {} }",
        "net/minecraft/world/entity/ai/goal/Goal" to "package net.minecraft.world.entity.ai.goal; public class Goal {}",
        "net/minecraft/world/entity/ai/attributes/Attribute" to "package net.minecraft.world.entity.ai.attributes; public class Attribute {}",
        "net/minecraft/world/entity/ai/attributes/Attributes" to "package net.minecraft.world.entity.ai.attributes; import net.minecraft.core.Holder; public class Attributes { public static Holder<Attribute> MAX_HEALTH; public static Holder<Attribute> MOVEMENT_SPEED; public static Holder<Attribute> ATTACK_DAMAGE; }",
        "net/minecraft/world/entity/ai/attributes/AttributeSupplier" to """package net.minecraft.world.entity.ai.attributes; import net.minecraft.core.Holder; public class AttributeSupplier {
            public static class Builder { public Builder add(Holder<Attribute> a, double v) { return this; } public AttributeSupplier build() { return null; } } }""",
        "net/minecraft/world/entity/Mob" to """package net.minecraft.world.entity; import net.minecraft.world.level.Level; import net.minecraft.world.entity.ai.goal.GoalSelector; import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
            public class Mob extends LivingEntity { protected GoalSelector goalSelector; protected GoalSelector targetSelector; public Mob(EntityType<? extends Mob> t, Level l) { super(t, l); }
              protected void registerGoals() {} public static AttributeSupplier.Builder createMobAttributes() { return null; } }""",
        "net/minecraft/world/entity/PathfinderMob" to "package net.minecraft.world.entity; import net.minecraft.world.level.Level; public class PathfinderMob extends Mob { public PathfinderMob(EntityType<? extends PathfinderMob> t, Level l) { super(t, l); } }",
        "net/minecraft/world/entity/monster/Monster" to """package net.minecraft.world.entity.monster; import net.minecraft.world.entity.*; import net.minecraft.world.level.Level; import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
            public class Monster extends PathfinderMob { public Monster(EntityType<? extends Monster> t, Level l) { super(t, l); } public static AttributeSupplier.Builder createMonsterAttributes() { return null; } }""",
        "net/minecraft/world/entity/player/Player" to "package net.minecraft.world.entity.player; import net.minecraft.world.entity.*; public class Player extends LivingEntity { public Player() { super(null, null); } }",
        "net/minecraft/world/entity/ai/goal/FloatGoal" to "package net.minecraft.world.entity.ai.goal; import net.minecraft.world.entity.Mob; public class FloatGoal extends Goal { public FloatGoal(Mob m) {} }",
        "net/minecraft/world/entity/ai/goal/MeleeAttackGoal" to "package net.minecraft.world.entity.ai.goal; import net.minecraft.world.entity.PathfinderMob; public class MeleeAttackGoal extends Goal { public MeleeAttackGoal(PathfinderMob m, double s, boolean f) {} }",
        "net/minecraft/world/entity/ai/goal/WaterAvoidingRandomStrollGoal" to "package net.minecraft.world.entity.ai.goal; import net.minecraft.world.entity.PathfinderMob; public class WaterAvoidingRandomStrollGoal extends Goal { public WaterAvoidingRandomStrollGoal(PathfinderMob m, double s) {} }",
        "net/minecraft/world/entity/ai/goal/LookAtPlayerGoal" to "package net.minecraft.world.entity.ai.goal; import net.minecraft.world.entity.Mob; public class LookAtPlayerGoal extends Goal { public LookAtPlayerGoal(Mob m, Class<?> c, float d) {} }",
        "net/minecraft/world/entity/ai/goal/target/HurtByTargetGoal" to "package net.minecraft.world.entity.ai.goal.target; import net.minecraft.world.entity.PathfinderMob; import net.minecraft.world.entity.ai.goal.Goal; public class HurtByTargetGoal extends Goal { public HurtByTargetGoal(PathfinderMob m, Class<?>... c) {} }",
        "net/minecraft/world/entity/ai/goal/target/NearestAttackableTargetGoal" to "package net.minecraft.world.entity.ai.goal.target; import net.minecraft.world.entity.Mob; import net.minecraft.world.entity.ai.goal.Goal; public class NearestAttackableTargetGoal<T> extends Goal { public NearestAttackableTargetGoal(Mob m, Class<T> c, boolean s) {} }",
        "org/joml/Vector3f" to "package org.joml; public class Vector3f {}",
        "net/minecraft/client/model/geom/ModelPart" to "package net.minecraft.client.model.geom; public class ModelPart {}",
        "net/minecraft/client/model/geom/ModelLayerLocation" to "package net.minecraft.client.model.geom; import net.minecraft.resources.ResourceLocation; public class ModelLayerLocation { public ModelLayerLocation(ResourceLocation r, String l) {} }",
        "net/minecraft/client/model/geom/PartPose" to "package net.minecraft.client.model.geom; public class PartPose { public static PartPose ZERO; public static PartPose offset(float x, float y, float z) { return null; } public static PartPose offsetAndRotation(float x, float y, float z, float a, float b, float c) { return null; } }",
        "net/minecraft/client/model/geom/builders/CubeDeformation" to "package net.minecraft.client.model.geom.builders; public class CubeDeformation { public CubeDeformation(float f) {} }",
        "net/minecraft/client/model/geom/builders/CubeListBuilder" to """package net.minecraft.client.model.geom.builders; public class CubeListBuilder { public static CubeListBuilder create() { return null; }
            public CubeListBuilder texOffs(int u, int v) { return this; } public CubeListBuilder mirror() { return this; }
            public CubeListBuilder addBox(float x, float y, float z, float w, float h, float d) { return this; }
            public CubeListBuilder addBox(float x, float y, float z, float w, float h, float d, CubeDeformation c) { return this; } }""",
        "net/minecraft/client/model/geom/builders/PartDefinition" to "package net.minecraft.client.model.geom.builders; import net.minecraft.client.model.geom.PartPose; public class PartDefinition { public PartDefinition addOrReplaceChild(String n, CubeListBuilder c, PartPose p) { return null; } }",
        "net/minecraft/client/model/geom/builders/MeshDefinition" to "package net.minecraft.client.model.geom.builders; public class MeshDefinition { public PartDefinition getRoot() { return null; } }",
        "net/minecraft/client/model/geom/builders/LayerDefinition" to "package net.minecraft.client.model.geom.builders; public class LayerDefinition { public static LayerDefinition create(MeshDefinition m, int w, int h) { return null; } }",
        "net/minecraft/client/animation/AnimationChannel" to """package net.minecraft.client.animation; public class AnimationChannel { public AnimationChannel(Target t, Keyframe... k) {}
            public interface Target {} public interface Interpolation {}
            public static class Targets { public static Target ROTATION; public static Target POSITION; public static Target SCALE; }
            public static class Interpolations { public static Interpolation LINEAR; public static Interpolation CATMULLROM; } }""",
        "net/minecraft/client/animation/Keyframe" to "package net.minecraft.client.animation; import org.joml.Vector3f; public class Keyframe { public Keyframe(float t, Vector3f v, AnimationChannel.Interpolation i) {} }",
        "net/minecraft/client/animation/KeyframeAnimations" to "package net.minecraft.client.animation; import org.joml.Vector3f; public class KeyframeAnimations { public static Vector3f degreeVec(float x, float y, float z) { return null; } public static Vector3f posVec(float x, float y, float z) { return null; } }",
        "net/minecraft/client/animation/AnimationDefinition" to """package net.minecraft.client.animation; public class AnimationDefinition { public static class Builder { public static Builder withLength(float l) { return null; }
            public Builder looping() { return this; } public Builder addAnimation(String b, AnimationChannel c) { return this; } public AnimationDefinition build() { return null; } } }""",
        "net/minecraft/client/model/EntityModel" to "package net.minecraft.client.model; import net.minecraft.world.entity.Entity; public abstract class EntityModel<T extends Entity> { public abstract void setupAnim(T e, float a, float b, float c, float d, float f); }",
        "net/minecraft/client/model/HierarchicalModel" to """package net.minecraft.client.model; import net.minecraft.world.entity.*; import net.minecraft.client.model.geom.ModelPart; import net.minecraft.client.animation.AnimationDefinition;
            public abstract class HierarchicalModel<E extends Entity> extends EntityModel<E> { public abstract ModelPart root();
              protected void animate(AnimationState s, AnimationDefinition d, float a, float sp) {} protected void animateWalk(AnimationDefinition d, float a, float b, float c, float e) {} }""",
        "net/minecraft/client/renderer/entity/EntityRendererProvider" to """package net.minecraft.client.renderer.entity; import net.minecraft.client.model.geom.*; public interface EntityRendererProvider<T> { Object create(Context c);
            public static class Context { public ModelPart bakeLayer(ModelLayerLocation l) { return null; } } }""",
        "net/minecraft/client/renderer/entity/EntityRenderer" to "package net.minecraft.client.renderer.entity; import net.minecraft.world.entity.Entity; import net.minecraft.resources.ResourceLocation; public abstract class EntityRenderer<T extends Entity> { public abstract ResourceLocation getTextureLocation(T e); }",
        "net/minecraft/client/renderer/entity/MobRenderer" to """package net.minecraft.client.renderer.entity; import net.minecraft.world.entity.Mob; import net.minecraft.client.model.EntityModel;
            public abstract class MobRenderer<T extends Mob, M extends EntityModel<T>> extends EntityRenderer<T> { public MobRenderer(EntityRendererProvider.Context c, M m, float s) {} }""",
        "net/minecraft/client/renderer/entity/EntityRenderers" to "package net.minecraft.client.renderer.entity; import net.minecraft.world.entity.EntityType; public class EntityRenderers { public static void register(EntityType<?> t, EntityRendererProvider<?> p) {} }",
        // ---- NeoForge
        "net/neoforged/bus/api/IEventBus" to "package net.neoforged.bus.api; public interface IEventBus { <T> void addListener(java.util.function.Consumer<T> c); }",
        "net/neoforged/fml/common/Mod" to "package net.neoforged.fml.common; import java.lang.annotation.*; @Retention(RetentionPolicy.RUNTIME) public @interface Mod { String value(); }",
        "net/neoforged/neoforge/registries/DeferredHolder" to "package net.neoforged.neoforge.registries; public class DeferredHolder<R, T extends R> implements java.util.function.Supplier<T> { public T get() { return null; } }",
        "net/neoforged/neoforge/registries/DeferredItem" to "package net.neoforged.neoforge.registries; import net.minecraft.world.item.Item; public class DeferredItem<T extends Item> extends DeferredHolder<Item, T> {}",
        "net/neoforged/neoforge/registries/DeferredBlock" to "package net.neoforged.neoforge.registries; import net.minecraft.world.level.block.Block; public class DeferredBlock<T extends Block> extends DeferredHolder<Block, T> {}",
        "net/neoforged/neoforge/registries/DeferredRegister" to """package net.neoforged.neoforge.registries; import java.util.function.*; import net.minecraft.resources.ResourceKey;
            import net.minecraft.world.item.*; import net.minecraft.world.level.block.Block; import net.minecraft.world.level.block.state.BlockBehaviour;
            public class DeferredRegister<T> { public static <T> DeferredRegister<T> create(ResourceKey k, String id) { return null; }
              public static Items createItems(String id) { return null; } public static Blocks createBlocks(String id) { return null; }
              public <I extends T> DeferredHolder<T, I> register(String n, Supplier<? extends I> s) { return null; }
              public void register(net.neoforged.bus.api.IEventBus b) {}
              public static class Items extends DeferredRegister<Item> {
                public <I extends Item> DeferredItem<I> registerItem(String n, Function<Item.Properties, ? extends I> f, Item.Properties p) { return null; }
                public DeferredItem<BlockItem> registerSimpleBlockItem(String n, Supplier<? extends Block> b, Item.Properties p) { return null; } }
              public static class Blocks extends DeferredRegister<Block> {
                public <B extends Block> DeferredBlock<B> registerBlock(String n, Function<BlockBehaviour.Properties, ? extends B> f, BlockBehaviour.Properties p) { return null; } } }""",
        "net/neoforged/neoforge/event/entity/EntityAttributeCreationEvent" to """package net.neoforged.neoforge.event.entity; import net.minecraft.world.entity.*; import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
            public class EntityAttributeCreationEvent { public void put(EntityType<? extends LivingEntity> t, AttributeSupplier s) {} }""",
        "net/neoforged/neoforge/client/event/EntityRenderersEvent" to """package net.neoforged.neoforge.client.event; import net.minecraft.client.model.geom.ModelLayerLocation; import net.minecraft.client.model.geom.builders.LayerDefinition;
            public class EntityRenderersEvent { public static class RegisterLayerDefinitions { public void registerLayerDefinition(ModelLayerLocation l, java.util.function.Supplier<LayerDefinition> s) {} } }"""
    )

    /** Fabric: i nomi «intermediary» come nelle mod pubblicate (l'app li porta ai nomi di Mojang). */
    private val STUBS_FABRIC = mapOf(
        "net/minecraft/class_2960" to "package net.minecraft; public class class_2960 { public static class_2960 method_60655(String a, String b) { return null; } }",
        "net/minecraft/class_2378" to "package net.minecraft; public interface class_2378<T> { static <V, T extends V> T method_10230(class_2378<V> r, class_2960 id, T v) { return v; } }",
        "net/minecraft/class_7923" to "package net.minecraft; public class class_7923 { public static class_2378 field_41178; }",
        "net/minecraft/class_1792" to "package net.minecraft; public class class_1792 { public class_1792(class_1793 s) {} public static class class_1793 { public class_1793 method_7889(int n) { return this; } public class_1793 method_7895(int n) { return this; } } }",
        "net/fabricmc/api/ModInitializer" to "package net.fabricmc.api; public interface ModInitializer { void onInitialize(); }"
    )

    private val NEOFORGE_MOD = mapOf(
        "com/example/testcode/TestCodeMod" to """package com.example.testcode; import net.neoforged.bus.api.IEventBus; import net.neoforged.fml.common.Mod;
            import net.neoforged.neoforge.event.entity.EntityAttributeCreationEvent;
            @Mod("testcode") public class TestCodeMod {
              public TestCodeMod(IEventBus bus) { ModItems.ITEMS.register(bus); ModBlocks.BLOCKS.register(bus); ModEntities.ENTITIES.register(bus); bus.addListener(this::attributes); }
              private void attributes(EntityAttributeCreationEvent e) { e.put(ModEntities.GOBLIN.get(), Goblin.createAttributes().build()); } }""",
        "com/example/testcode/ModTiers" to """package com.example.testcode; import net.minecraft.world.item.Tier;
            public enum ModTiers implements Tier { RUBY(3, 1200, 7.5F, 3.0F, 18);
              private final int level, uses, ench; private final float speed, damage;
              ModTiers(int level, int uses, float speed, float damage, int ench) { this.level = level; this.uses = uses; this.speed = speed; this.damage = damage; this.ench = ench; }
              public int getUses() { return uses; } public float getSpeed() { return speed; } public float getAttackDamageBonus() { return damage; }
              public int getLevel() { return level; } public int getEnchantmentValue() { return ench; } }""",
        "com/example/testcode/ModArmor" to """package com.example.testcode; import net.minecraft.world.item.*;
            public enum ModArmor implements ArmorMaterial { RUBY(30, new int[]{3, 8, 6, 3}, 16, 1.5F, 0.0F);
              private static final int[] BASE = {11, 16, 15, 13};
              private final int factor, ench; private final int[] protection; private final float tough, kb;
              ModArmor(int factor, int[] protection, int ench, float tough, float kb) { this.factor = factor; this.protection = protection; this.ench = ench; this.tough = tough; this.kb = kb; }
              public int getDurabilityForType(ArmorItem.Type t) { return BASE[t.ordinal()] * factor; } public int getDefenseForType(ArmorItem.Type t) { return protection[t.ordinal()]; }
              public int getEnchantmentValue() { return ench; } public float getToughness() { return tough; } public float getKnockbackResistance() { return kb; } }""",
        "com/example/testcode/ModItems" to """package com.example.testcode; import net.minecraft.world.item.*; import net.minecraft.world.food.FoodProperties; import net.minecraft.world.effect.*;
            import net.neoforged.neoforge.registries.*;
            public class ModItems { public static final DeferredRegister.Items ITEMS = DeferredRegister.createItems("testcode");
              public static final DeferredItem<Item> RUBY = ITEMS.registerItem("ruby", Item::new, new Item.Properties().stacksTo(16));
              public static final DeferredHolder<Item, SwordItem> RUBY_SWORD = ITEMS.register("ruby_sword", () -> new SwordItem(ModTiers.RUBY, 3, -2.4F, new Item.Properties()));
              public static final DeferredHolder<Item, Item> TOMATO = ITEMS.register("tomato", () -> new Item(new Item.Properties().food(new FoodProperties.Builder().nutrition(4).saturationModifier(0.3F).effect(new MobEffectInstance(MobEffects.REGENERATION, 100, 1), 1.0F).build())));
              public static final DeferredHolder<Item, ArmorItem> RUBY_HELMET = ITEMS.register("ruby_helmet", () -> new ArmorItem(ModArmor.RUBY, ArmorItem.Type.HELMET, new Item.Properties()));
              public static final DeferredItem<BlockItem> RUBY_BLOCK = ITEMS.registerSimpleBlockItem("ruby_block", ModBlocks.RUBY_BLOCK, new Item.Properties());
              public static final DeferredHolder<Item, Item>[] GEMS = gems();
              @SuppressWarnings("unchecked") private static DeferredHolder<Item, Item>[] gems() {
                String[] colors = {"red", "green", "blue"}; DeferredHolder<Item, Item>[] out = new DeferredHolder[colors.length];
                for (int i = 0; i < colors.length; i++) out[i] = ITEMS.register(colors[i] + "_gem", () -> new Item(new Item.Properties()));
                return out; } }""",
        "com/example/testcode/ModBlocks" to """package com.example.testcode; import net.minecraft.world.level.block.*; import net.minecraft.world.level.block.state.BlockBehaviour;
            import net.minecraft.util.valueproviders.UniformInt; import net.neoforged.neoforge.registries.*;
            public class ModBlocks { public static final DeferredRegister.Blocks BLOCKS = DeferredRegister.createBlocks("testcode");
              public static final DeferredBlock<Block> RUBY_BLOCK = BLOCKS.registerBlock("ruby_block", Block::new, BlockBehaviour.Properties.of().strength(5.0F, 6.0F).sound(SoundType.METAL).lightLevel(s -> 7).requiresCorrectToolForDrops().friction(0.98F));
              public static final DeferredHolder<Block, DropExperienceBlock> RUBY_ORE = BLOCKS.register("ruby_ore", () -> new DropExperienceBlock(UniformInt.of(3, 7), BlockBehaviour.Properties.of().strength(3.0F))); }""",
        "com/example/testcode/ModEntities" to """package com.example.testcode; import net.minecraft.world.entity.*; import net.minecraft.core.registries.Registries; import net.neoforged.neoforge.registries.*;
            public class ModEntities { public static final DeferredRegister<EntityType<?>> ENTITIES = DeferredRegister.create(Registries.ENTITY_TYPE, "testcode");
              public static final DeferredHolder<EntityType<?>, EntityType<Goblin>> GOBLIN = ENTITIES.register("goblin", () -> EntityType.Builder.of(Goblin::new, MobCategory.MONSTER).sized(0.6F, 1.95F).build("goblin")); }""",
        "com/example/testcode/Goblin" to """package com.example.testcode; import net.minecraft.world.entity.*; import net.minecraft.world.entity.monster.Monster; import net.minecraft.world.entity.player.Player;
            import net.minecraft.world.entity.ai.goal.*; import net.minecraft.world.entity.ai.goal.target.*; import net.minecraft.world.entity.ai.attributes.*; import net.minecraft.world.level.Level;
            public class Goblin extends Monster { public final AnimationState idleState = new AnimationState();
              public Goblin(EntityType<? extends Monster> t, Level l) { super(t, l); }
              public static AttributeSupplier.Builder createAttributes() { return Monster.createMonsterAttributes().add(Attributes.MAX_HEALTH, 24.0).add(Attributes.MOVEMENT_SPEED, 0.3).add(Attributes.ATTACK_DAMAGE, 4.0); }
              @Override protected void registerGoals() {
                this.goalSelector.addGoal(0, new FloatGoal(this)); this.goalSelector.addGoal(2, new MeleeAttackGoal(this, 1.2, false));
                this.goalSelector.addGoal(7, new WaterAvoidingRandomStrollGoal(this, 1.0)); this.goalSelector.addGoal(8, new LookAtPlayerGoal(this, Player.class, 8.0F));
                this.targetSelector.addGoal(1, new HurtByTargetGoal(this)); this.targetSelector.addGoal(2, new NearestAttackableTargetGoal<>(this, Player.class, true)); } }""",
        "com/example/testcode/client/GoblinAnimations" to """package com.example.testcode.client; import net.minecraft.client.animation.*;
            public class GoblinAnimations {
              public static final AnimationDefinition WALK = AnimationDefinition.Builder.withLength(1.0F).looping()
                .addAnimation("left_leg", new AnimationChannel(AnimationChannel.Targets.ROTATION,
                   new Keyframe(0.0F, KeyframeAnimations.degreeVec(30.0F, 0.0F, 0.0F), AnimationChannel.Interpolations.LINEAR),
                   new Keyframe(0.5F, KeyframeAnimations.degreeVec(-30.0F, 0.0F, 0.0F), AnimationChannel.Interpolations.CATMULLROM))).build();
              public static final AnimationDefinition IDLE = AnimationDefinition.Builder.withLength(2.0F).looping()
                .addAnimation("head", new AnimationChannel(AnimationChannel.Targets.POSITION, new Keyframe(0.0F, KeyframeAnimations.posVec(0.0F, 1.0F, 0.0F), AnimationChannel.Interpolations.LINEAR))).build(); }""",
        "com/example/testcode/client/GoblinModel" to """package com.example.testcode.client; import com.example.testcode.Goblin; import net.minecraft.client.model.HierarchicalModel; import net.minecraft.client.model.geom.*;
            import net.minecraft.client.model.geom.builders.*; import net.minecraft.resources.ResourceLocation;
            public class GoblinModel extends HierarchicalModel<Goblin> {
              public static final ModelLayerLocation LAYER = new ModelLayerLocation(ResourceLocation.fromNamespaceAndPath("testcode", "goblin"), "main");
              private final ModelPart root; public GoblinModel(ModelPart root) { this.root = root; }
              public static LayerDefinition createBodyLayer() { MeshDefinition mesh = new MeshDefinition(); PartDefinition r = mesh.getRoot();
                PartDefinition body = r.addOrReplaceChild("body", CubeListBuilder.create().texOffs(0, 16).addBox(-4.0F, -12.0F, -2.0F, 8.0F, 12.0F, 4.0F), PartPose.offset(0.0F, 12.0F, 0.0F));
                body.addOrReplaceChild("head", CubeListBuilder.create().texOffs(0, 0).addBox(-4.0F, -8.0F, -4.0F, 8.0F, 8.0F, 8.0F, new CubeDeformation(0.5F)), PartPose.offset(0.0F, -12.0F, 0.0F));
                r.addOrReplaceChild("left_leg", CubeListBuilder.create().texOffs(0, 32).mirror().addBox(-2.0F, 0.0F, -2.0F, 4.0F, 12.0F, 4.0F), PartPose.offsetAndRotation(2.0F, 12.0F, 0.0F, 0.1F, 0.0F, 0.0F));
                return LayerDefinition.create(mesh, 64, 64); }
              @Override public ModelPart root() { return root; }
              @Override public void setupAnim(Goblin e, float a, float b, float c, float d, float f) { this.animateWalk(GoblinAnimations.WALK, a, b, 2.0F, 2.5F); this.animate(e.idleState, GoblinAnimations.IDLE, c, 1.0F); } }""",
        "com/example/testcode/client/GoblinRenderer" to """package com.example.testcode.client; import com.example.testcode.Goblin; import net.minecraft.client.renderer.entity.*; import net.minecraft.resources.ResourceLocation;
            public class GoblinRenderer extends MobRenderer<Goblin, GoblinModel> {
              static final ResourceLocation TEX = ResourceLocation.fromNamespaceAndPath("testcode", "textures/entity/goblin.png");
              public GoblinRenderer(EntityRendererProvider.Context c) { super(c, new GoblinModel(c.bakeLayer(GoblinModel.LAYER)), 0.5F); }
              @Override public ResourceLocation getTextureLocation(Goblin g) { return TEX; } }""",
        "com/example/testcode/client/ClientSetup" to """package com.example.testcode.client; import com.example.testcode.ModEntities; import net.minecraft.client.renderer.entity.EntityRenderers;
            import net.neoforged.neoforge.client.event.EntityRenderersEvent;
            public class ClientSetup { public static void renderers() { EntityRenderers.register(ModEntities.GOBLIN.get(), GoblinRenderer::new); }
              public static void layers(EntityRenderersEvent.RegisterLayerDefinitions e) { e.registerLayerDefinition(GoblinModel.LAYER, GoblinModel::createBodyLayer); } }"""
    )

    private val FABRIC_MOD = mapOf(
        "com/example/fab/FabItems" to """package com.example.fab; import net.minecraft.*;
            public class FabItems { public static final class_1792 GEM = register("gem", new class_1792(new class_1792.class_1793().method_7889(8)));
              public static final class_1792 HAMMER = register("hammer", new class_1792(new class_1792.class_1793().method_7895(500)));
              static class_1792 register(String id, class_1792 item) { return class_2378.method_10230(class_7923.field_41178, class_2960.method_60655("fabmod", id), item); }
              public static void init() {} }""",
        "com/example/fab/FabMod" to """package com.example.fab; public class FabMod implements net.fabricmc.api.ModInitializer { public void onInitialize() { FabItems.init(); } }"""
    )

    /** Compila stub + mod e restituisce solo le classi della mod (come in un JAR pubblicato). */
    private fun compile(stubs: Map<String, String>, mod: Map<String, String>): Map<String, ByteArray> {
        val javac = ToolProvider.getSystemJavaCompiler() ?: throw IllegalStateException("Serve un JDK per compilare le mod di prova")
        val dir = Files.createTempDirectory("mmi-javamod").toFile()
        try {
            val src = File(dir, "src"); val out = File(dir, "out")
            val files = ArrayList<String>()
            for ((name, code) in stubs + mod) {
                val f = File(src, "$name.java"); f.parentFile.mkdirs(); f.writeText(code); files.add(f.path)
            }
            out.mkdirs()
            val err = java.io.ByteArrayOutputStream()
            val rc = javac.run(null, null, err, *(listOf("-nowarn", "-d", out.path, "--release", "17") + files).toTypedArray())
            if (rc != 0) throw IllegalStateException("Compilazione della mod di prova fallita:\n" + err.toString())
            val classes = LinkedHashMap<String, ByteArray>()
            val prefixes = mod.keys.map { it.substringBeforeLast('/') + "/" }.toSet()
            out.walkTopDown().filter { it.isFile && it.name.endsWith(".class") }.forEach { f ->
                val rel = f.relativeTo(out).path.replace(File.separatorChar, '/')
                if (prefixes.any { rel.startsWith(it) }) classes[rel] = f.readBytes()
            }
            return classes
        } finally {
            dir.deleteRecursively()
        }
    }

    fun neoforge(): Map<String, ByteArray> {
        val e = LinkedHashMap<String, ByteArray>()
        e["META-INF/neoforge.mods.toml"] = "modLoader=\"javafml\"\nloaderVersion=\"[1,)\"\n[[mods]]\nmodId=\"testcode\"\nversion=\"1.0\"\ndisplayName=\"Test Code\"\n".toByteArray()
        e["assets/testcode/textures/entity/goblin.png"] = TestMod.png(64, 64)
        e["assets/testcode/lang/en_us.json"] = """{"entity.testcode.goblin":"Goblin","item.testcode.ruby":"Ruby"}""".toByteArray()
        e.putAll(compile(STUBS_MOJANG, NEOFORGE_MOD))
        return e
    }

    fun fabric(): Map<String, ByteArray> {
        val e = LinkedHashMap<String, ByteArray>()
        e["fabric.mod.json"] = """{"schemaVersion":1,"id":"fabmod","version":"1.0","name":"Fab Mod","entrypoints":{"main":["com.example.fab.FabMod"]}}""".toByteArray()
        e.putAll(compile(STUBS_FABRIC, FABRIC_MOD))
        return e
    }
}
