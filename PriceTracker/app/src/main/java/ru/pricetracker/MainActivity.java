package ru.pricetracker;

import android.app.*;
import android.os.*;
import android.graphics.Color;
import android.content.*;
import android.view.*;
import android.widget.*;
import java.util.*;
import java.util.concurrent.*;

public class MainActivity extends Activity {
    PriceDb db;
    LinearLayout list;
    TextView summary;
    ExecutorService pool = Executors.newFixedThreadPool(4);

    @Override public void onCreate(Bundle b) {
        super.onCreate(b);
        db = new PriceDb(this);
        buildUi();
        refresh();
    }

    private void buildUi() {
        ScrollView scroll = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(32, 36, 32, 32);

        TextView title = new TextView(this);
        title.setText("МОНИТОРИНГ ЦЕН");
        title.setTextSize(26);
        title.setTextColor(Color.BLACK);
        title.setPadding(0,0,0,18);
        root.addView(title);

        Button add = new Button(this);
        add.setText("＋ ДОБАВИТЬ URL");
        add.setOnClickListener(v -> addUrlDialog());
        root.addView(add);

        Button check = new Button(this);
        check.setText("ПРОВЕРИТЬ ЦЕНЫ");
        check.setOnClickListener(v -> checkAll());
        root.addView(check);

        summary = new TextView(this);
        summary.setTextSize(16);
        summary.setPadding(0, 18, 0, 18);
        root.addView(summary);

        list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        root.addView(list);

        scroll.addView(root);
        setContentView(scroll);
    }

    private void addUrlDialog() {
        EditText input = new EditText(this);
        input.setHint("https://www.podrygka.ru/...");
        input.setSingleLine(false);

        new AlertDialog.Builder(this)
            .setTitle("Добавить товар или раздел")
            .setMessage("Первая версия поддерживает ссылки Подружки.")
            .setView(input)
            .setPositiveButton("Добавить", (d,w) -> {
                String url = input.getText().toString().trim();
                if (!url.isEmpty()) addUrl(url);
            })
            .setNegativeButton("Отмена", null)
            .show();
    }

    private void addUrl(String url) {
        Toast.makeText(this, "Получаю цену…", Toast.LENGTH_SHORT).show();
        pool.execute(() -> {
            try {
                if (url.contains("podrygka.ru")) {
                    PodrygkaParser.Result r = PodrygkaParser.product(url);
                    db.add(url, r.name, r.price, r.oldPrice);
                    runOnUiThread(() -> { refresh(); toast("Добавлено: " + r.name); });
                } else {
                    throw new Exception("Пока поддерживается только Podrygka");
                }
            } catch (Exception e) {
                runOnUiThread(() -> toast("Ошибка: " + e.getMessage()));
            }
        });
    }

    private void checkAll() {
        List<PriceDb.Product> products = db.all();
        if (products.isEmpty()) {
            toast("Сначала добавь товар");
            return;
        }
        summary.setText("Проверяю " + products.size() + " товаров…");
        pool.execute(() -> {
            int down=0, up=0, same=0, errors=0;
            for (PriceDb.Product p : products) {
                try {
                    PodrygkaParser.Result r = PodrygkaParser.product(p.url);
                    double old = p.lastPrice;
                    String status;
                    if (r.price < old - 0.001) { status = "down"; down++; }
                    else if (r.price > old + 0.001) { status = "up"; up++; }
                    else { status = "same"; same++; }
                    db.updatePrice(p.id, r.price, status);
                } catch (Exception e) {
                    errors++;
                }
            }
            int fDown=down, fUp=up, fSame=same, fErr=errors;
            runOnUiThread(() -> {
                summary.setText("🟢 Подешевели: " + fDown +
                        "   🔴 Подорожали: " + fUp +
                        "   ⚪ Без изменений: " + fSame +
                        "   ⚠ Ошибки: " + fErr);
                refresh();
            });
        });
    }

    private void refresh() {
        if (list == null) return;
        list.removeAllViews();
        List<PriceDb.Product> ps = db.all();
        summary.setText("Товаров сохранено: " + ps.size());
        for (PriceDb.Product p : ps) {
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.VERTICAL);
            row.setPadding(0, 18, 0, 18);

            TextView name = new TextView(this);
            name.setText((p.name == null || p.name.isEmpty()) ? p.url : p.name);
            name.setTextSize(17);
            name.setTextColor(Color.BLACK);

            TextView price = new TextView(this);
            String arrow = "";
            if ("down".equals(p.status)) arrow = "  🟢 ↓";
            if ("up".equals(p.status)) arrow = "  🔴 ↑";
            if ("same".equals(p.status)) arrow = "  ⚪ =";
            price.setText(String.format(Locale.US, "%.0f ₽%s", p.lastPrice, arrow));
            price.setTextSize(16);

            row.addView(name);
            row.addView(price);

            row.setOnLongClickListener(v -> {
                new AlertDialog.Builder(this)
                    .setTitle("Удалить товар?")
                    .setMessage(p.name)
                    .setPositiveButton("Удалить", (d,w) -> { db.delete(p.id); refresh(); })
                    .setNegativeButton("Отмена", null).show();
                return true;
            });
            list.addView(row);
        }
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_LONG).show();
    }

    @Override protected void onDestroy() {
        pool.shutdownNow();
        db.close();
        super.onDestroy();
    }
}