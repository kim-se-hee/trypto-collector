package ksh.tryptocollector.matching;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

record MatchResult(
        List<MatchedOrderMessage.Item> items,
        Set<String> buyOrderIds,
        Set<String> sellOrderIds
) {

    boolean isEmpty() {
        return items.isEmpty();
    }

    static MatchResult fromRedis(Set<String> buyOrderIds, Set<String> sellOrderIds, BigDecimal price) {
        List<MatchedOrderMessage.Item> items = new ArrayList<>();
        for (String id : buyOrderIds) {
            items.add(new MatchedOrderMessage.Item(Long.valueOf(id), price));
        }
        for (String id : sellOrderIds) {
            items.add(new MatchedOrderMessage.Item(Long.valueOf(id), price));
        }
        return new MatchResult(items, buyOrderIds, sellOrderIds);
    }

    static MatchResult fromDb(List<Long> buyIds, List<Long> sellIds, BigDecimal price) {
        List<MatchedOrderMessage.Item> items = new ArrayList<>();
        for (Long id : buyIds) {
            items.add(new MatchedOrderMessage.Item(id, price));
        }
        for (Long id : sellIds) {
            items.add(new MatchedOrderMessage.Item(id, price));
        }
        return new MatchResult(items, Set.of(), Set.of());
    }
}
