package com.willfp.ecoskills

import com.github.benmanes.caffeine.cache.Caffeine
import com.willfp.eco.util.NamespacedKeyUtils
import com.willfp.ecoskills.api.modifier.ItemStatModifier
import com.willfp.ecoskills.api.modifier.ModifierOperation
import com.willfp.ecoskills.api.modifier.PlayerStatModifier
import com.willfp.ecoskills.api.modifier.StatModifier
import com.willfp.ecoskills.stats.Stat
import com.willfp.ecoskills.stats.Stats
import org.bukkit.NamespacedKey
import org.bukkit.entity.Player
import org.bukkit.inventory.EquipmentSlot
import org.bukkit.inventory.ItemStack
import org.bukkit.inventory.meta.ItemMeta
import org.bukkit.persistence.PersistentDataContainer
import org.bukkit.persistence.PersistentDataType
import java.util.concurrent.TimeUnit

private val plugin = EcoSkillsPlugin.getInstance()

/*
Item Modifiers
 */

private val modifierKey: NamespacedKey = NamespacedKeyUtils.create("ecoskills", "modifiers")
private val statKey: NamespacedKey = NamespacedKeyUtils.create("ecoskills", "stat")
private val amountKey: NamespacedKey = NamespacedKeyUtils.create("ecoskills", "amount")
private val slotsKey: NamespacedKey = NamespacedKeyUtils.create("ecoskills", "slots")
private val operationKey: NamespacedKey = NamespacedKeyUtils.create("ecoskills", "operation")

private fun PersistentDataContainer.applyModifiers(tag: PersistentDataContainer) {
    this.set(modifierKey, PersistentDataType.TAG_CONTAINER, tag)
}

private fun getModifiersTag(meta: ItemMeta): PersistentDataContainer {
    val container = meta.persistentDataContainer
    val context = container.adapterContext
    return container.getOrDefault(modifierKey, PersistentDataType.TAG_CONTAINER, context.newPersistentDataContainer())
}

fun ItemStack.addStatModifier(modifier: ItemStatModifier) {
    val meta = this.itemMeta ?: return
    val modifiers = getModifiersTag(meta)

    modifiers.remove(modifier.key)

    val modifierTag = modifiers.adapterContext.newPersistentDataContainer()

    modifierTag.set(statKey, PersistentDataType.STRING, modifier.stat.id)
    modifierTag.set(amountKey, PersistentDataType.DOUBLE, modifier.amount)
    modifierTag.set(
        slotsKey,
        PersistentDataType.STRING,
        modifier.slots.joinToString(separator = ",") { it.name }
    )
    modifierTag.set(operationKey, PersistentDataType.STRING, modifier.operation.name)
    modifiers.set(modifier.key, PersistentDataType.TAG_CONTAINER, modifierTag)

    meta.persistentDataContainer.applyModifiers(modifiers)

    this.itemMeta = meta
}

fun ItemStack.removeStatModifier(key: NamespacedKey) {
    val meta = this.itemMeta ?: return
    val modifiers = getModifiersTag(meta)

    modifiers.remove(key)

    meta.persistentDataContainer.applyModifiers(modifiers)

    this.itemMeta = meta
}

fun ItemStack.getStatModifierKeys(): MutableSet<NamespacedKey> {
    val meta = this.itemMeta ?: return HashSet()
    val modifiers = getModifiersTag(meta)

    return modifiers.keys
}

fun ItemStack.getStatModifiers(): MutableSet<ItemStatModifier> {
    val keys = HashSet<ItemStatModifier>()
    for (modifier in this.getStatModifierKeys().stream().map { key -> this.getStatModifier(key) }) {
        if (modifier != null) {
            keys.add(modifier)
        }
    }
    return keys
}

fun ItemStack.getStatModifier(key: NamespacedKey): ItemStatModifier? {
    val meta = this.itemMeta ?: return null
    val modifiers = getModifiersTag(meta)

    return if (modifiers.has(key, PersistentDataType.TAG_CONTAINER)) {
        val modifierTag = modifiers.get(key, PersistentDataType.TAG_CONTAINER)!!

        val stat = Stats.getByID(modifierTag.get(statKey, PersistentDataType.STRING)!!)!!
        val amount = modifierTag.get(amountKey, PersistentDataType.DOUBLE)!!
        val slots = modifierTag.get(slotsKey, PersistentDataType.STRING)!!
            .split(",")
            .filterNot { it.isBlank() }
            .mapNotNull { s -> runCatching { EquipmentSlot.valueOf(s) }.getOrNull() }
        val operation = ModifierOperation.valueOf(modifierTag.get(operationKey, PersistentDataType.STRING)!!)

        ItemStatModifier(key, stat, amount, operation, *slots.toTypedArray())
    } else {
        null
    }
}

/*
Player Modifiers
 */

private const val META_MODIFIERS = "ecoskills_modifiers"
private const val META_STAT_KEY = "stat"
private const val META_AMOUNT_KEY = "amount"
private const val META_OPERATION_KEY = "operation"

@Suppress("UNCHECKED_CAST")
private val modifierCache = Caffeine.newBuilder()
    .expireAfterWrite(200, TimeUnit.MILLISECONDS)
    .build<Player, MutableMap<String, Any>> {
        it.getMetadata(META_MODIFIERS).getOrNull(0)?.value() as MutableMap<String, Any>? ?: mutableMapOf()
    }

private fun Player.applyModifiers(meta: MutableMap<String, Any>) {
    this.setMetadata(META_MODIFIERS, plugin.metadataValueFactory.create(meta))
}

@Suppress("UNCHECKED_CAST")
private fun getModifiersTag(player: Player): MutableMap<String, Any> {
    return modifierCache.get(player)
}

@JvmOverloads
fun Player.addStatModifier(modifier: StatModifier, shouldUpdate: Boolean = true) {
    val modifiers = getModifiersTag(this)

    modifiers[modifier.key.toString()] = mapOf(
        Pair(META_STAT_KEY, modifier.stat.id),
        Pair(META_AMOUNT_KEY, modifier.amount),
        Pair(META_OPERATION_KEY, modifier.operation.name)
    )

    this.applyModifiers(modifiers)

    if (shouldUpdate) {
        modifier.stat.updateStatLevel(this)
    }
}

@JvmOverloads
fun Player.removeStatModifier(key: NamespacedKey, shouldUpdate: Boolean = true): Stat? {
    val modifiers = getModifiersTag(this)

    @Suppress("UNCHECKED_CAST")
    val stat = Stats.getByID(
        (modifiers[key.toString()]
                as? MutableMap<String, Any>)
            ?.get(META_STAT_KEY) as? String ?: ""
    )

    modifiers.remove(key.toString())

    this.applyModifiers(modifiers)

    if (shouldUpdate) {
        stat?.updateStatLevel(this)
    }

    return stat
}

fun Player.getStatModifierKeys(): MutableSet<NamespacedKey> {
    val modifiers = getModifiersTag(this)
    return modifiers.keys.map { NamespacedKeyUtils.fromString(it) }.toMutableSet()
}

fun Player.getStatModifiers(): MutableSet<PlayerStatModifier> {
    val keys = HashSet<PlayerStatModifier>()
    for (modifier in this.getStatModifierKeys().stream().map { key -> this.getStatModifier(key) }) {
        if (modifier != null) {
            keys.add(modifier)
        }
    }
    return keys
}

@Suppress("UNCHECKED_CAST")
fun Player.getStatModifier(key: NamespacedKey): PlayerStatModifier? {
    val modifiers = getModifiersTag(this)

    return if (modifiers.containsKey(key.toString())) {
        val modifier = modifiers[key.toString()] as Map<String, Any>

        val stat = Stats.getByID(modifier[META_STAT_KEY] as String)!!
        val amount = modifier[META_AMOUNT_KEY] as Double
        val operation = ModifierOperation.valueOf(modifier[META_OPERATION_KEY] as String)

        PlayerStatModifier(key, stat, amount, operation)
    } else {
        null
    }
}