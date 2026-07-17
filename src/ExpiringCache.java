import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Function;
import java.util.function.LongSupplier;

/**
 * Thread-safe fixed-capacity cache with per-entry TTL and LRU eviction.
 * get/put are average O(1). size() may be O(n) when purging expired entries.
 */
public class ExpiringCache<K, V> {

    private final int capacity;
    private final LongSupplier clock;
    private final ReentrantLock lock = new ReentrantLock();
    private final Map<K, Node<K, V>> map = new HashMap<>();
    private Node<K, V> head; // MRU
    private Node<K, V> tail; // LRU

    public ExpiringCache(int capacity) {
        this(capacity, System::currentTimeMillis);
    }

    public ExpiringCache(int capacity, LongSupplier clock) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("capacity must be positive");
        }
        if (clock == null) {
            throw new IllegalArgumentException("clock must be non-null");
        }
        this.capacity = capacity;
        this.clock = clock;
    }

    public V get(K key) {
        requireKey(key);
        lock.lock();
        try {
            Node<K, V> node = map.get(key);
            if (node == null) {
                return null;
            }
            if (isExpired(node)) {
                removeNode(node);
                return null;
            }
            moveToHead(node);
            return node.value;
        } finally {
            lock.unlock();
        }
    }

    public void put(K key, V value, long ttlMillis) {
        requireKey(key);
        requireValue(value);
        requireTtl(ttlMillis);
        long expireAt = clock.getAsLong() + ttlMillis;
        lock.lock();
        try {
            Node<K, V> existing = map.get(key);
            if (existing != null) {
                existing.value = value;
                existing.expireAtMillis = expireAt;
                moveToHead(existing);
                return;
            }
            // Capacity / eviction added in Task 2
            Node<K, V> node = new Node<>(key, value, expireAt);
            map.put(key, node);
            addToHead(node);
        } finally {
            lock.unlock();
        }
    }

    public V computeIfAbsent(K key, long ttlMillis, Function<K, V> loader) {
        throw new UnsupportedOperationException("not implemented yet");
    }

    public int size() {
        lock.lock();
        try {
            return map.size(); // expiry-aware size in Task 3
        } finally {
            lock.unlock();
        }
    }

    private boolean isExpired(Node<K, V> node) {
        return node.expireAtMillis <= clock.getAsLong();
    }

    private void requireKey(K key) {
        if (key == null) {
            throw new IllegalArgumentException("key must be non-null");
        }
    }

    private void requireValue(V value) {
        if (value == null) {
            throw new IllegalArgumentException("value must be non-null");
        }
    }

    private void requireTtl(long ttlMillis) {
        if (ttlMillis <= 0) {
            throw new IllegalArgumentException("ttlMillis must be positive");
        }
    }

    private void addToHead(Node<K, V> node) {
        node.prev = null;
        node.next = head;
        if (head != null) {
            head.prev = node;
        }
        head = node;
        if (tail == null) {
            tail = node;
        }
    }

    private void moveToHead(Node<K, V> node) {
        if (node == head) {
            return;
        }
        unlink(node);
        addToHead(node);
    }

    private void unlink(Node<K, V> node) {
        if (node.prev != null) {
            node.prev.next = node.next;
        } else {
            head = node.next;
        }
        if (node.next != null) {
            node.next.prev = node.prev;
        } else {
            tail = node.prev;
        }
        node.prev = null;
        node.next = null;
    }

    private void removeNode(Node<K, V> node) {
        unlink(node);
        map.remove(node.key);
    }

    private static final class Node<K, V> {
        final K key;
        V value;
        long expireAtMillis;
        Node<K, V> prev;
        Node<K, V> next;

        Node(K key, V value, long expireAtMillis) {
            this.key = key;
            this.value = value;
            this.expireAtMillis = expireAtMillis;
        }
    }
}
