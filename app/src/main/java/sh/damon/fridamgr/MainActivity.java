package sh.damon.fridamgr;

import android.os.Bundle;
import android.text.InputFilter;
import android.view.View;
import android.widget.Button;
import android.widget.CompoundButton;
import android.widget.TextView;

import androidx.activity.EdgeToEdge;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import com.google.android.material.materialswitch.MaterialSwitch;
import com.google.android.material.textfield.TextInputEditText;

import java.io.File;
import java.util.Random;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import sh.damon.fridamgr.filter.Uint16Filter;
import sh.damon.fridamgr.preferences.Preferences;
import sh.damon.fridamgr.preferences.UserPreferences;

public class MainActivity extends AppCompatActivity {
    final ExecutorService executorService =
            new ThreadPoolExecutor(2, 4, 15_000, TimeUnit.MILLISECONDS, new LinkedBlockingQueue<>());

    FridaServer server;

    Button btnUpdateInstallFrida;
    Button btnServerStateSwitch;

    TextView fridaStateLbl;
    TextView fridaVersionLbl;

    MaterialSwitch switchStartOnBoot;
    MaterialSwitch switchListenOnNetwork;

    TextInputEditText inputPortNumber;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        UserPreferences.load(new File(getFilesDir(), "fridaMgr.json"));

        server = new FridaServer(getFilesDir());

        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);
        setContentView(R.layout.activity_main);
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main), (v, insets) -> {
            Insets systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom);
            return insets;
        });

        fridaStateLbl = findViewById(R.id.frida_server_state);
        fridaVersionLbl = findViewById(R.id.frida_version);

        btnUpdateInstallFrida = findViewById(R.id.btn_install_update_server);
        btnUpdateInstallFrida.setOnClickListener((View v) -> {
            if (server.getState() == FridaServer.State.NOT_INSTALLED) {
                executorService.execute(server::install);
            }
            else {
                executorService.execute(server::update);
            }
        });

        btnServerStateSwitch = findViewById(R.id.btn_toggle_server);
        btnServerStateSwitch.setOnClickListener((View v) -> {
            final FridaServer.State state = server.getState();
            if (state == FridaServer.State.RUNNING) {
                executorService.execute(server::kill);
            }
            else if (state == FridaServer.State.STOPPED) {
                executorService.execute(server::start);
            }
        });

        switchStartOnBoot = findViewById(R.id.switch_start_on_boot);
        switchListenOnNetwork = findViewById(R.id.switch_listen_on_network);
        inputPortNumber = findViewById(R.id.input_port_number);
        inputPortNumber.setFilters(new InputFilter[]{
                new Uint16Filter()
        });

        switchStartOnBoot.setOnCheckedChangeListener((CompoundButton view, boolean startOnBoot) -> UserPreferences.set(Preferences.START_ON_BOOT, startOnBoot));

        switchListenOnNetwork.setOnCheckedChangeListener((CompoundButton view, boolean listenOnNetwork) -> {
            UserPreferences.set(Preferences.LISTEN_ON_NETWORK, listenOnNetwork);

            inputPortNumber.setVisibility(listenOnNetwork ? View.VISIBLE : View.INVISIBLE);
        });

        inputPortNumber.setOnFocusChangeListener((View v, boolean hasFocus) -> {
            if (!hasFocus) {
                int portNumber = Integer.parseInt(String.valueOf(inputPortNumber.getText()));
                executorService.execute(() -> server.toggleListenPort(switchListenOnNetwork.isEnabled(), portNumber));

                UserPreferences.set(Preferences.PORT_NUMBER, portNumber);
            }
        });

        loadUserPreferences();
        registerEventListeners();
        updateElements();
    }

    private void loadUserPreferences() {
        final boolean startOnBoot = UserPreferences.get(Preferences.START_ON_BOOT, false);
        switchStartOnBoot.setChecked(startOnBoot);

        final boolean listenOnNetwork = UserPreferences.get(Preferences.LISTEN_ON_NETWORK, false);
        switchListenOnNetwork.setChecked(listenOnNetwork);
        inputPortNumber.setVisibility(listenOnNetwork ? View.VISIBLE : View.INVISIBLE);

        int portNumber = UserPreferences.get(Preferences.PORT_NUMBER, 27055);
        if (portNumber < 1000 || portNumber > 65535) {
            // random between 20k and 40k
            portNumber = (new Random()).nextInt(20_001) + 20_000;
            UserPreferences.set(Preferences.PORT_NUMBER, portNumber);
        }
        inputPortNumber.setText(String.valueOf(portNumber));
        server.toggleListenPort(listenOnNetwork, portNumber);
    }

    private void onSharedPreferenceChange(UserPreferences prefs, String key) {
        executorService.execute(UserPreferences::save);
    }

    private void registerEventListeners() {
        server.setOnUpdateListener(() -> runOnUiThread(this::updateElements));

        UserPreferences.setOnPrefChangeCallback(this::onSharedPreferenceChange);
    }

    private void updateElements() {
        fridaStateLbl.setText(String.format(getString(R.string.frida_server_state_label), getString(server.getStateStringId())));
        fridaVersionLbl.setText(String.format(getString(R.string.frida_version), server.getVersion()));

        btnUpdateInstallFrida.setEnabled(true);
        btnServerStateSwitch.setEnabled(true);
        btnUpdateInstallFrida.setText(R.string.btn_update_frida);

        switch (server.getState()) {
            case RUNNING: {
                btnServerStateSwitch.setText(R.string.btn_kill_frida_server);

                break;
            }
            case STOPPED: {
                btnServerStateSwitch.setText(R.string.btn_start_frida_server);

                break;
            }
            case NOT_INSTALLED: {
                btnServerStateSwitch.setEnabled(false);
                btnUpdateInstallFrida.setText(R.string.btn_install_frida);

                break;
            }
            case STARTING:
            case STOPPING:
                btnServerStateSwitch.setEnabled(false);

                break;
            case UPDATING: {
                btnUpdateInstallFrida.setEnabled(false);
                btnUpdateInstallFrida.setText(String.format(getString(R.string.btn_download_progress), server.getDownloadState().getProgress()));
            }
        }
    }
}