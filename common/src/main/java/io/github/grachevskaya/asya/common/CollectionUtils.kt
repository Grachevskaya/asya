package io.github.grachevskaya.asya.common

object CollectionUtils {
    inline fun <K, V> MutableMap<K, V>.removeIf(predicate: (K, V) -> Boolean) {
        this.filter { (key, value) -> predicate(key, value) }.forEach { this.remove(it.key) }
    }

    inline fun <K, V> MutableMap<K, V>.removeIfWithCount(predicate: (K, V) -> Boolean): Int =
        this.filter { (key, value) -> predicate(key, value) }.count {
            this.remove(it.key) != null
        }

    inline fun <K> MutableSet<K>.removeIfWithCount(predicate: (K) -> Boolean): Int =
        this.filter { key -> predicate(key) }.count {
            this.remove(it)
        }

    inline fun <reified T> Array<*>.firstWithType(): T = this.first { it is T } as T

    inline fun <reified T> Array<*>.firstOrNullWithType(): T? = this.firstOrNull { it is T } as? T

    inline fun <reified T> Array<*>.lastWithType(): T = this.last { it is T } as T

    inline fun <reified T> Array<*>.lastOrNullWithType(): T? = this.lastOrNull { it is T } as? T

    /*
     * The source is copied first: syncing a collection with itself would otherwise read it after
     * it was cleared, and answer an empty collection.
     */
    inline fun <reified T> MutableList<T>.sync(elements: Array<T>) {
        val copy = elements.toList()

        clear()
        addAll(copy)
    }

    inline fun <reified T> MutableList<T>.sync(elements: Iterable<T>) {
        val copy = elements.toList()

        clear()
        addAll(copy)
    }

    inline fun <reified T> MutableSet<T>.sync(elements: Iterable<T>) {
        val copy = elements.toList()

        clear()
        addAll(copy)
    }

    inline fun <reified K, reified V> MutableMap<K, V>.sync(from: Map<K, V>) {
        val copy = from.toMap()

        clear()
        putAll(copy)
    }
}
