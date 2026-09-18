import io.github.dfa1.zstd.Zstd;
import io.github.dfa1.zstd.ZstdByteSize;
import io.github.dfa1.zstd.ZstdCompressContext;
import io.github.dfa1.zstd.ZstdCompressionLevel;
import io.github.dfa1.zstd.ZstdDictionary;
import net.datafaker.Faker;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.zip.GZIPOutputStream;

/// Compresses an order-shaped JSON payload — one order, a 32 KB page of
/// orders, a 512 KB page of orders — with identity, gzip (default/3/6), and
/// zstd/dcz (levels 3/6, a shared 2 KiB dictionary trained on 300 samples).
/// The order JSON's varying fields (name, email, address, tracking number,
/// sku, item name) come from DataFaker, so the payload has realistic entropy
/// rather than cycling through a handful of fixed values. Reproduces the
/// byte-size table in "Pick a compression level before reaching for a
/// dictionary", in the RFC 9842 for Microservices article.
///
/// Run: javac against zstd-ffm's `zstd` module and datafaker on the
/// classpath, then `java --enable-native-access=ALL-UNNAMED`.
public final class SizeCompareFaker {

    private static final long TRAINING_SEED = 0x5EED;
    private static final long MEASURE_SEED = 2;

    public static void main(String[] args) throws Exception {
        List<byte[]> trainingSamples = new ArrayList<>();
        Faker trainingFaker = new Faker(new Random(TRAINING_SEED));
        for (int i = 0; i < 300; i++) {
            trainingSamples.add(order(trainingFaker).getBytes(StandardCharsets.UTF_8));
        }
        ZstdDictionary dict = ZstdDictionary.train(trainingSamples, ZstdByteSize.ofKiB(2));

        report(dict, "small", singleOrder());
        report(dict, "32 KB", ordersPage(32 * 1024L));
        report(dict, "512 KB", ordersPage(512 * 1024L));
        System.out.println();
        System.out.printf("dictionary size: %d B%n", dict.toByteArray().length);
    }

    private static void report(ZstdDictionary dict, String label, byte[] payload) throws Exception {
        System.out.printf("== %s (%d B) ==%n", label, payload.length);
        System.out.printf("identity %d%n", payload.length);
        System.out.printf("gzip default %d%n", gzip(payload, -1).length);
        System.out.printf("gzip L3 %d%n", gzip(payload, 3).length);
        System.out.printf("gzip L6 %d%n", gzip(payload, 6).length);
        for (int level : new int[]{3, 6}) {
            ZstdCompressionLevel lvl = new ZstdCompressionLevel(level);
            byte[] zstdNoDict = Zstd.compress(payload, lvl);
            try (ZstdCompressContext ctx = new ZstdCompressContext().level(lvl)) {
                byte[] zstdDict = ctx.compress(payload, dict);
                System.out.printf("zstd L%d %d%n", level, zstdNoDict.length);
                System.out.printf("dcz L%d %d%n", level, zstdDict.length);
            }
        }
    }

    private static byte[] singleOrder() {
        return order(new Faker(new Random(MEASURE_SEED))).getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] ordersPage(long targetBytes) {
        Faker faker = new Faker(new Random(MEASURE_SEED));
        StringBuilder page = new StringBuilder("[");
        boolean first = true;
        while (page.length() < targetBytes) {
            if (!first) {
                page.append(",");
            }
            page.append(order(faker));
            first = false;
        }
        page.append("]");
        return page.toString().getBytes(StandardCharsets.UTF_8);
    }

    private static String order(Faker faker) {
        String status = faker.options().option("shipped", "processing", "delivered", "cancelled", "returned");
        String carrier = faker.options().option("DHL", "UPS", "FedEx", "Swiss Post");
        int qty1 = 1 + faker.random().nextInt(5);
        int unitPrice1 = 500 + faker.random().nextInt(5000);
        int totalCents = 500 + faker.random().nextInt(20000);
        return """
                {"id":"ord_%s","status":"%s","createdAt":"%s","updatedAt":"%s",\
                "customer":{"id":"cus_%s","email":"%s","name":"%s"},\
                "items":[{"sku":"SKU-%04d","name":"%s","qty":%d,"unitPriceCents":%d}],\
                "shipping":{"carrier":"%s","trackingId":"%s","address":{"line1":"%s","city":"%s",\
                "postalCode":"%s","country":"%s"}},"currency":"CHF","totalCents":%d,\
                "links":{"self":"https://api.example.com/orders/ord_%s","customer":"https://api.example.com/customers/cus_%s"}}\
                """.formatted(
                faker.internet().uuid().substring(0, 8), status,
                faker.timeAndDate().past(30, java.util.concurrent.TimeUnit.DAYS).toString(),
                faker.timeAndDate().past(1, java.util.concurrent.TimeUnit.DAYS).toString(),
                faker.internet().uuid().substring(0, 8), faker.internet().emailAddress(), faker.name().fullName(),
                faker.random().nextInt(9999), faker.commerce().productName(), qty1, unitPrice1,
                carrier, faker.number().digits(18),
                faker.address().streetAddress(), faker.address().city(),
                faker.address().zipCode(), faker.address().countryCode(),
                totalCents,
                faker.internet().uuid().substring(0, 8), faker.internet().uuid().substring(0, 8));
    }

    private static byte[] gzip(byte[] data, int level) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (GZIPOutputStream gz = new GZIPOutputStream(out, Math.min(65536, Math.max(512, data.length))) {
            {
                if (level >= 0) {
                    def.setLevel(level);
                }
            }
        }) {
            gz.write(data);
        }
        return out.toByteArray();
    }
}
