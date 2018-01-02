package panda.lulu.cache;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;

public class HotSpotDataCache<K, V> extends AbstractCacheMap<K, V> {

	public HotSpotDataCache(int cacheSize, long defaultExpire) {

		super(cacheSize, defaultExpire);

		/**
		 * @param initialCapacity
		 *            the initial capacity  长度
		 * @param loadFactor
		 *            the load factor 哈希因子
		 * @param accessOrder
		 *            the ordering mode - <tt>true</tt> for access-order LRU,
		 *            <tt>false</tt> for insertion-order FIFO
		 */
		this.cacheMap = new LinkedHashMap<K, CacheObject<K, V>>(cacheSize + 1,
				1f, true) {
			private static final long serialVersionUID = 1L;

			@Override
			protected boolean removeEldestEntry(
					Map.Entry<K, CacheObject<K, V>> eldest) {

				return HotSpotDataCache.this.removeEldestEntry(eldest);
			}

		};
	}

	/**
	 * 如果LinkedHashMap长度大于设置的缓存，则需要删除最久未使用的数据
	 * @param eldest
	 * @return
	 */
	private boolean removeEldestEntry(Map.Entry<K, CacheObject<K, V>> eldest) {

		if (cacheSize == 0)
			return false;

		return size() > cacheSize;
	}

	/**
	 * 如果再有超长的情况就清除过期数据即可，这种情况极少出现
	 */
	@Override
	protected int eliminateCache() {

		if (!isNeedClearExpiredObject()) {
			return 0;
		}

		Iterator<CacheObject<K, V>> iterator = cacheMap.values().iterator();
		int count = 0;
		while (iterator.hasNext()) {
			CacheObject<K, V> cacheObject = iterator.next();

			if (cacheObject.isExpired()) {
				iterator.remove();
				count++;
			}
		}

		return count;
	}

}
