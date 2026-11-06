package com.example.medibook.repositories;

import com.example.medibook.models.UserProfile;
import com.example.medibook.utils.SessionPolicy;
import com.google.firebase.FirebaseNetworkException;
import com.google.firebase.FirebaseTooManyRequestsException;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseAuthException;
import com.google.firebase.auth.FirebaseAuthUserCollisionException;
import com.google.firebase.auth.FirebaseAuthWeakPasswordException;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.firestore.DocumentSnapshot;
import com.google.firebase.firestore.FieldValue;
import com.google.firebase.firestore.FirebaseFirestore;
import com.google.firebase.firestore.FirebaseFirestoreException;
import com.google.firebase.firestore.Source;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * One in-process authentication operation at a time. Activities observe immutable state,
 * so a rotation does not repeat a request or abandon profile creation/cleanup.
 * Activities detach listeners onStop; SDK callbacks do not capture Activity references.
 * Public operations and listeners run on the main thread. Passwords are not retained in state.
 */
public final class AuthRepository {

    public enum Error {
        NONE, NETWORK, TOO_MANY_REQUESTS, EMAIL_IN_USE, WEAK_PASSWORD,
        INVALID_CREDENTIALS, AUTH_UNAVAILABLE, PERMISSION_DENIED, PROFILE_MISSING,
        INVALID_PROFILE, ROLE_MISMATCH, PROFILE_FAILED_ACCOUNT_REMOVED,
        PROFILE_FAILED_ACCOUNT_REMAINS, UNKNOWN
    }

    public static final class State {
        public final boolean loading;
        public final UserProfile user;
        public final Error error;

        private State(boolean loading, UserProfile user, Error error) {
            this.loading = loading;
            this.user = user;
            this.error = error;
        }
    }

    public interface Listener {
        void onAuthStateChanged(State state);
    }

    private static AuthRepository instance;

    private final FirebaseAuth auth = FirebaseAuth.getInstance();
    private final FirebaseFirestore firestore = FirebaseFirestore.getInstance();
    private final Set<Listener> listeners = new HashSet<>();
    private State state = new State(false, null, Error.NONE);
    private int operationId;

    private AuthRepository() {
    }

    public static synchronized AuthRepository getInstance() {
        if (instance == null) {
            instance = new AuthRepository();
        }
        return instance;
    }

    public void addListener(Listener listener) {
        listeners.add(listener);
        listener.onAuthStateChanged(state);
    }

    public void removeListener(Listener listener) {
        listeners.remove(listener);
    }

    public void signIn(String email, String password) {
        if (state.loading) return;
        int operation = beginOperation();
        auth.signInWithEmailAndPassword(email, password).addOnCompleteListener(task -> {
            if (operation != operationId) return;
            if (!task.isSuccessful()) {
                fail(operation, mapError(task.getException()));
                return;
            }
            verifySession(auth.getCurrentUser(), operation, false);
        });
    }

    public void register(String fullName, String email, String phone, String password) {
        if (state.loading) return;
        int operation = beginOperation();
        auth.createUserWithEmailAndPassword(email, password).addOnCompleteListener(task -> {
            if (operation != operationId) return;
            if (!task.isSuccessful()) {
                fail(operation, mapError(task.getException()));
                return;
            }
            FirebaseUser user = auth.getCurrentUser();
            if (user == null) {
                fail(operation, Error.UNKNOWN);
                return;
            }

            Map<String, Object> profile = new HashMap<>();
            profile.put("fullName", fullName);
            profile.put("email", user.getEmail());
            profile.put("phone", phone);
            profile.put("role", UserProfile.Role.PATIENT.getProfileValue());
            profile.put("createdAt", FieldValue.serverTimestamp());

            // Completion means the server accepted the write, not just the local cache.
            firestore.collection("users").document(user.getUid()).set(profile)
                    .addOnCompleteListener(profileTask -> {
                        if (operation != operationId) return;
                        if (profileTask.isSuccessful()) {
                            verifySession(user, operation, true);
                        } else {
                            rollbackNewAccount(user, operation);
                        }
                    });
        });
    }

    public void checkSession() {
        if (state.loading) return;
        FirebaseUser user = auth.getCurrentUser();
        if (user == null) {
            publish(new State(false, null, Error.NONE));
            return;
        }
        verifySession(user, beginOperation(), false);
    }

    public void signOut() {
        // Late session-check callbacks must not reopen either logged-out protected screen.
        operationId++;
        auth.signOut();
        publish(new State(false, null, Error.NONE));
    }

    private int beginOperation() {
        int operation = ++operationId;
        publish(new State(true, null, Error.NONE));
        return operation;
    }

    private void verifySession(FirebaseUser user, int operation, boolean patientRegistration) {
        if (user == null) {
            fail(operation, Error.INVALID_CREDENTIALS);
            return;
        }
        // Reload detects deleted/disabled accounts; refresh claims for every session gate.
        user.reload().addOnCompleteListener(reloadTask -> {
            if (operation != operationId) return;
            if (!reloadTask.isSuccessful()) {
                fail(operation, mapError(reloadTask.getException()));
                return;
            }
            user.getIdToken(true).addOnCompleteListener(tokenTask -> {
                if (operation != operationId) return;
                if (!tokenTask.isSuccessful()) {
                    fail(operation, mapError(tokenTask.getException()));
                    return;
                }
                Map<String, Object> claims = tokenTask.getResult().getClaims();
                firestore.collection("users").document(user.getUid()).get(Source.SERVER)
                        .addOnCompleteListener(profileTask -> {
                            if (operation != operationId) return;
                            if (!profileTask.isSuccessful()) {
                                fail(operation, mapError(profileTask.getException()));
                                return;
                            }
                            acceptProfile(profileTask.getResult(), claims, user.getEmail(),
                                    operation, patientRegistration);
                        });
            });
        });
    }

    private void acceptProfile(DocumentSnapshot document, Map<String, Object> claims,
                               String authenticatedEmail, int operation, boolean patientRegistration) {
        if (!document.exists()) {
            fail(operation, Error.PROFILE_MISSING);
            return;
        }
        SessionPolicy.Result result = patientRegistration
                ? SessionPolicy.evaluatePatientRegistration(claims, document.getData(), authenticatedEmail)
                : SessionPolicy.evaluate(claims, document.getData(), authenticatedEmail);
        if (result.failure == SessionPolicy.Failure.ROLE_MISMATCH) {
            fail(operation, Error.ROLE_MISMATCH);
            return;
        }
        if (result.failure != SessionPolicy.Failure.NONE) {
            fail(operation, Error.INVALID_PROFILE);
            return;
        }
        publish(new State(false, result.user, Error.NONE));
    }

    private void rollbackNewAccount(FirebaseUser user, int operation) {
        // Auth and Firestore cannot share a transaction. Only delete the NEW account whose
        // profile write was rejected. Never delete an existing account on a login/read failure.
        user.delete().addOnCompleteListener(deleteTask -> {
            if (operation != operationId) return;
            fail(operation, deleteTask.isSuccessful()
                    ? Error.PROFILE_FAILED_ACCOUNT_REMOVED : Error.PROFILE_FAILED_ACCOUNT_REMAINS);
        });
    }

    private void fail(int operation, Error error) {
        if (operation != operationId) return;
        auth.signOut();
        publish(new State(false, null, error));
    }

    private void publish(State newState) {
        state = newState;
        for (Listener listener : new ArrayList<>(listeners)) {
            listener.onAuthStateChanged(newState);
        }
    }

    private Error mapError(Exception exception) {
        if (exception instanceof FirebaseNetworkException) return Error.NETWORK;
        if (exception instanceof FirebaseTooManyRequestsException) return Error.TOO_MANY_REQUESTS;
        if (exception instanceof FirebaseAuthUserCollisionException) return Error.EMAIL_IN_USE;
        if (exception instanceof FirebaseAuthWeakPasswordException) return Error.WEAK_PASSWORD;
        if (exception instanceof FirebaseFirestoreException) {
            FirebaseFirestoreException.Code code = ((FirebaseFirestoreException) exception).getCode();
            if (code == FirebaseFirestoreException.Code.PERMISSION_DENIED) return Error.PERMISSION_DENIED;
            if (code == FirebaseFirestoreException.Code.UNAVAILABLE
                    || code == FirebaseFirestoreException.Code.DEADLINE_EXCEEDED) return Error.NETWORK;
        }
        if (exception instanceof FirebaseAuthException) {
            String code = ((FirebaseAuthException) exception).getErrorCode();
            if ("ERROR_OPERATION_NOT_ALLOWED".equals(code)) return Error.AUTH_UNAVAILABLE;
            return Error.INVALID_CREDENTIALS;
        }
        return Error.UNKNOWN;
    }
}
