package com.example.heat_map_java_test_ux;

import android.content.Intent;
import android.os.Bundle;
import android.widget.ImageButton;
import android.widget.TextView;
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
import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.FirebaseDatabase;

import java.util.HashMap;
import java.util.Map;

public class SignUpActivity extends AppCompatActivity {

    private TextInputEditText emailEditText, passwordEditText, confirmPasswordEditText, usernameEditText;
    private FirebaseAuth mAuth;
    private DatabaseReference mDatabase;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);
        setContentView(R.layout.activity_sign_up);

        mAuth = FirebaseAuth.getInstance();
        mDatabase = FirebaseDatabase.getInstance("https://heatmap-48e81-default-rtdb.europe-west1.firebasedatabase.app/").getReference("users");

        emailEditText = findViewById(R.id.email_edit_text);
        usernameEditText = findViewById(R.id.username_edit_text);
        passwordEditText = findViewById(R.id.password_edit_text);
        confirmPasswordEditText = findViewById(R.id.confirm_password_edit_text);
        MaterialButton signUpButton = findViewById(R.id.sign_up_button);
        ImageButton backButton = findViewById(R.id.back_button);
        TextView loginLink = findViewById(R.id.login_link);

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main), (v, insets) -> {
            Insets systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom);
            return insets;
        });

        backButton.setOnClickListener(v -> finish());
        loginLink.setOnClickListener(v -> finish());

        signUpButton.setOnClickListener(v -> {
            String email = emailEditText.getText().toString().trim();
            String username = usernameEditText.getText().toString().trim();
            String password = passwordEditText.getText().toString().trim();
            String confirmPassword = confirmPasswordEditText.getText().toString().trim();

            if (email.isEmpty() || username.isEmpty() || password.isEmpty() || confirmPassword.isEmpty()) {
                Toast.makeText(this, "Please fill all fields", Toast.LENGTH_SHORT).show();
                return;
            }

            if (!password.equals(confirmPassword)) {
                Toast.makeText(this, "Passwords do not match", Toast.LENGTH_SHORT).show();
                return;
            }

            registerUser(email, username, password);
        });
    }

    private void registerUser(String email, String username, String password) {
        mAuth.createUserWithEmailAndPassword(email, password)
                .addOnCompleteListener(this, task -> {
                    if (task.isSuccessful()) {
                        FirebaseUser user = mAuth.getCurrentUser();
                        if (user != null) {
                            saveUserToDatabase(user.getUid(), username, user.getEmail());
                        }
                    } else {
                        Toast.makeText(SignUpActivity.this, "Error: " + task.getException().getMessage(), Toast.LENGTH_LONG).show();
                    }
                });
    }

    private void saveUserToDatabase(String userId, String username, String email) {
        // Fix for Issue 8: Check if user already exists to prevent resetting stats
        mDatabase.child(userId).get().addOnCompleteListener(task -> {
            if (task.isSuccessful()) {
                if (task.getResult().exists()) {
                    // User already has historical data (stats, color, etc.)
                    // Only update username and email to preserve stats
                    Map<String, Object> updates = new HashMap<>();
                    updates.put("username", username);
                    updates.put("email", email);
                    
                    mDatabase.child(userId).updateChildren(updates).addOnCompleteListener(updateTask -> {
                        finalizeSignUp(updateTask.isSuccessful());
                    });
                } else {
                    // New user, save full object with defaults
                    User user = new User(userId, username, email);
                    mDatabase.child(userId).setValue(user).addOnCompleteListener(setValueTask -> {
                        finalizeSignUp(setValueTask.isSuccessful());
                    });
                }
            } else {
                Toast.makeText(this, "Database check failed: " + task.getException().getMessage(), Toast.LENGTH_SHORT).show();
            }
        });
    }

    private void finalizeSignUp(boolean success) {
        if (success) {
            Toast.makeText(SignUpActivity.this, "Success!", Toast.LENGTH_SHORT).show();
            startActivity(new Intent(SignUpActivity.this, MapActivity.class));
            finishAffinity();
        } else {
            Toast.makeText(this, "Failed to save user data.", Toast.LENGTH_SHORT).show();
        }
    }
}
