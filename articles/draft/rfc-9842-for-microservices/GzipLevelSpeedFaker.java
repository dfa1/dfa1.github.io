import io.github.dfa1.zstd.Zstd;
import io.github.dfa1.zstd.ZstdCompressionLevel;
import net.datafaker.Faker;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Random;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/// Isolated in-process timing (no server, no network) for gzip
/// default/3/6 vs zstd 3/6, compressing and decompressing the same
/// DataFaker order-page payload SizeCompareFaker measures for size. Reproduces
/// the speed figures in "Pick a compression level before reaching for a
/// dictionary", in the RFC 9842 for Microservices article.
///
/// Run: javac against zstd-ffm's `zstd` module and datafaker on the
/// classpath, then `java --enable-native-access=ALL-UNNAMED`.
public final class GzipLevelSpeedFaker {

    public static void main(String[] args) throws Exception {
        for (int size : new int[]{32 * 1024, 512 * 1024}) {
            byte[] payload = ordersPage(size);
            time("gzip default", size, () -> gzip(payload, -1), () -> gunzip(gzip(payload, -1)));
            time("gzip L3", size, () -> gzip(payload, 3), () -> gunzip(gzip(payload, 3)));
            time("gzip L6", size, () -> gzip(payload, 6), () -> gunzip(gzip(payload, 6)));
            time("zstd L3", size, () -> Zstd.compress(payload, new ZstdCompressionLevel(3)),
                    () -> Zstd.decompress(Zstd.compress(payload, new ZstdCompressionLevel(3))));
            time("zstd L6", size, () -> Zstd.compress(payload, new ZstdCompressionLevel(6)),
                    () -> Zstd.decompress(Zstd.compress(payload, new ZstdCompressionLevel(6))));
        }
    }

    interface Thrower<T> { T get() throws Exception; }

    private static void time(String label, int size, Thrower<byte[]> compress, Thrower<byte[]> roundTrip) throws Exception {
        for (int i = 0; i < 200; i++) { compress.get(); roundTrip.get(); }
        int n = 1500;
        long start = System.nanoTime();
        for (int i = 0; i < n; i++) { compress.get(); }
        long compressNanos = System.nanoTime() - start;
        start = System.nanoTime();
        for (int i = 0; i < n; i++) { roundTrip.get(); }
        long roundTripNanos = System.nanoTime() - start;
        System.out.printf("%-13s %7d B  compress %8.1f us/op   compress+decompress %8.1f us/op%n",
                label, size, compressNanos / 1000.0 / n, roundTripNanos / 1000.0 / n);
    }

    private static byte[] ordersPage(long targetBytes) {
        Faker faker = new Faker(new Random(2));
        StringBuilder page = new StringBuilder("[");
        boolean first = true;
        while (page.length() < targetBytes) {
            if (!first) page.append(",");
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
            { if (level >= 0) def.setLevel(level); }
        }) {
            gz.write(data);
        }
        return out.toByteArray();
    }

    private static byte[] gunzip(byte[] data) throws Exception {
        try (GZIPInputStream gz = new GZIPInputStream(new ByteArrayInputStream(data))) {
            return gz.readAllBytes();
        }
    }
}
