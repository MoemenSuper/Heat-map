package com.example.heat_map_java_test_ux;

import android.content.Intent;
import android.os.Bundle;
import android.util.Log;
import android.util.Patterns;
import android.widget.Toast;
import androidx.activity.EdgeToEdge;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.textfield.TextInputEditText;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;

public class MainActivity extends AppCompatActivity {

    private static final String TAG = "MainActivity";
    private TextInputEditText emailEditText;
    private TextInputEditText passwordEditText;
    private FirebaseAuth mAuth;

    @Override
    protected void onStart() {
        super.onStart();
        // Check if user is signed in (non-null) and update UI accordingly.
        FirebaseUser currentUser = mAuth.getCurrentUser();
        if(currentUser != null){
            goToMap();
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);
        setContentView(R.layout.activity_main);

        // Initialize Firebase Auth
        mAuth = FirebaseAuth.getInstance();

        emailEditText = findViewById(R.id.email_edit_text);
        passwordEditText = findViewById(R.id.password_edit_text);
        MaterialButton signInButton = findViewById(R.id.sign_in_button);
        MaterialButton signUpButtonLink = findViewById(R.id.sign_up_button_link);

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main), (v, insets) -> {
            Insets systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom);
            return insets;
        });

        // Link to SignUp page via the new Button
        signUpButtonLink.setOnClickListener(v -> {
            Intent intent = new Intent(MainActivity.this, SignUpActivity.class);
            startActivity(intent);
        });

        signInButton.setOnClickListener(v -> {
            if (emailEditText.getText() != null && passwordEditText.getText() != null) {
                String email = emailEditText.getText().toString().trim();
                String password = passwordEditText.getText().toString().trim();

                if (validateInput(email, password)) {
                    signInUser(email, password);
                }
            }
        });
    }

    private boolean validateInput(String email, String password) {
        if (email.isEmpty()) {
            emailEditText.setError("Email is required");
            emailEditText.requestFocus();
            return false;
        }

        if (!Patterns.EMAIL_ADDRESS.matcher(email).matches()) {
            emailEditText.setError("Please enter a valid email");
            emailEditText.requestFocus();
            return false;
        }

        if (password.isEmpty()) {
            passwordEditText.setError("Password is required");
            passwordEditText.requestFocus();
            return false;
        }

        return true;
    }

    private void signInUser(String email, String password) {
        mAuth.signInWithEmailAndPassword(email, password)
                .addOnCompleteListener(this, task -> {
                    if (task.isSuccessful()) {
                        Log.d(TAG, "signInWithEmail:success");
                        goToMap();
                    } else {
                        Log.w(TAG, "signInWithEmail:failure", task.getException());
                        String errorMessage = (task.getException() != null) ? task.getException().getMessage() : "Authentication failed";
                        Toast.makeText(MainActivity.this, errorMessage, Toast.LENGTH_LONG).show();
                    }
                });
    }

    private void goToMap() {
        Intent intent = new Intent(MainActivity.this, MapActivity.class);
        startActivity(intent);
        finish();
    }
}
