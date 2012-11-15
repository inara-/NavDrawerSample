
package jp.inara.sample.navdrawer;

import android.app.Activity;
import android.os.Bundle;
import android.view.Menu;
import android.widget.ArrayAdapter;
import android.widget.ListView;

public class MainActivity extends Activity {

    static final String[] sample = {
            "Apple", "Banana", "Cherry", "Grape", "Melon", "Lemon", "Orange", "Peach",
            "Water melon", "Apple", "Banana", "Cherry", "Grape", "Melon", "Lemon", "Orange",
            "Peach", "Water melon"
    };

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        
        ListView list = (ListView) findViewById(R.id.nav);
        ArrayAdapter<String> adapter = new ArrayAdapter<String>(this, android.R.layout.simple_list_item_1, sample);
        list.setAdapter(adapter);

        ListView content = (ListView) findViewById(R.id.content);
        ArrayAdapter<String> adapter2 = new ArrayAdapter<String>(this, R.layout.content_row, R.id.text, sample);
        content.setAdapter(adapter2);
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.activity_main, menu);
        return true;
    }
}
