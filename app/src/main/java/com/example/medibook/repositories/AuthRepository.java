package com.example.medibook.repositories;

import com.example.medibook.models.UserProfile;
import com.example.medibook.utils.SessionPolicy;
import com.example.medibook.utils.VerifiedSessionLease;
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
        PROFILE_FAILED_ACCOUNT_REMAINS, SESSION_EXPIRED, UNKNOWN
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
    private String verifiedUid, observedUid, profileUid, checkingUid;
    private long verifiedAt = -1, tokenExpiresAt;
    private boolean changingAccount;
    private boolean checkingExisting;

    private AuthRepository() {
        observedUid = currentUid();
        auth.addAuthStateListener(firebase -> {
            String uid = currentUid();
            if (!java.util.Objects.equals(observedUid, uid)) {
                observedUid = uid;
                // SDK state notifications can arrive after the sign-in Task and even its profile
                // read. Never cancel our own already-verified/current-UID operation as an account swap.
                if (uid != null && uid.equals(profileUid) && state.user != null) return;
                invalidateVerifiedSession();
                if (!state.loading || (!changingAccount && !java.util.Objects.equals(checkingUid, uid))) {
                    operationId++;
                    profileUid = null;
                    publish(new State(false, null, uid == null && (state.user != null || checkingExisting) ? Error.SESSION_EXPIRED : Error.NONE));
                }
            }
        });
        // A new SDK token invalidates the lease; subsequent navigation rechecks signed claims/profile.
        auth.addIdTokenListener((FirebaseAuth.IdTokenListener) firebase -> invalidateVerifiedSession());
    }

    private String currentUid() { return auth.getCurrentUser() == null ? null : auth.getCurrentUser().getUid(); }
    public void invalidateVerifiedSession() { verifiedUid = null; verifiedAt = -1; tokenExpiresAt = 0; }

    public static synchronized AuthRepository getInstance() {
        if (instance == null) {
            instance = new AuthRepository();
        }
        return instance;
    }

    public void addListener(Listener listener) {
        if (state.user != null && !java.util.Objects.equals(profileUid, currentUid())) {
            state = new State(false, null, Error.NONE);
        }
        listeners.add(listener);
        listener.onAuthStateChanged(state);
    }

    public void removeListener(Listener listener) {
        listeners.remove(listener);
    }

    public void signIn(String email, String password) {
        if (state.loading) return;
        int operation = beginOperation();
        checkingExisting = false;
        changingAccount = true;
        auth.signInWithEmailAndPassword(email, password).addOnCompleteListener(task -> {
            if (operation != operationId) return;
            changingAccount = false;
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
        checkingExisting = false;
        changingAccount = true;
        auth.createUserWithEmailAndPassword(email, password).addOnCompleteListener(task -> {
            if (operation != operationId) return;
            changingAccount = false;
            if (!task.isSuccessful()) {
                fail(operation, mapError(task.getException()));
                return;
            }
            FirebaseUser user = auth.getCurrentUser();
            if (user == null) {
                fail(operation, Error.UNKNOWN);
                return;
            }
            checkingUid = user.getUid(); // Own Auth callback may arrive during the profile write.

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
            invalidateVerifiedSession();
            publish(new State(false, null, Error.NONE));
            return;
        }
        if (state.user != null && VerifiedSessionLease.reusable(verifiedUid, user.getUid(), verifiedAt,
                android.os.SystemClock.elapsedRealtime(), tokenExpiresAt, System.currentTimeMillis())) return;
        checkingExisting = true;
        verifySession(user, beginOperation(), false);
    }

    public void signOut() {
        // Late session-check callbacks must not reopen either logged-out protected screen.
        operationId++;
        changingAccount = false;
        checkingExisting = false;
        profileUid = null; checkingUid = null;
        invalidateVerifiedSession();
        com.example.medibook.local.LocalPatientServices.signOutBestEffort();
        auth.signOut();
        observedUid = currentUid();
        publish(new State(false, null, Error.NONE));
    }

    private int beginOperation() {
        int operation = ++operationId;
        checkingUid = null;
        publish(new State(true, null, Error.NONE));
        return operation;
    }

    private void verifySession(FirebaseUser user, int operation, boolean patientRegistration) {
        if (user == null) {
            fail(operation, Error.INVALID_CREDENTIALS);
            return;
        }
        checkingUid = user.getUid();
        // Cold/expired leases still reload the account, refresh signed claims and fetch the profile.
        user.reload().addOnCompleteListener(reloadTask -> {
            if (operation != operationId || !user.getUid().equals(currentUid())) return;
            if (!reloadTask.isSuccessful()) {
                fail(operation, mapError(reloadTask.getException()));
                return;
            }
            user.getIdToken(true).addOnCompleteListener(tokenTask -> {
                if (operation != operationId || !user.getUid().equals(currentUid())) return;
                if (!tokenTask.isSuccessful()) {
                    fail(operation, mapError(tokenTask.getException()));
                    return;
                }
                Map<String, Object> claims = tokenTask.getResult().getClaims();
                firestore.collection("users").document(user.getUid()).get(Source.SERVER)
                        .addOnCompleteListener(profileTask -> {
                            if (operation != operationId || !user.getUid().equals(currentUid())) return;
                            if (!profileTask.isSuccessful()) {
                                fail(operation, mapError(profileTask.getException()));
                                return;
                            }
                            acceptProfile(profileTask.getResult(), claims, user.getEmail(),
                                    operation, patientRegistration, tokenTask.getResult().getExpirationTimestamp() * 1000L);
                        });
            });
        });
    }

    private void acceptProfile(DocumentSnapshot document, Map<String, Object> claims,
                               String authenticatedEmail, int operation, boolean patientRegistration, long expiresAt) {
        if (operation != operationId || !document.getId().equals(currentUid())) return;
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
        profileUid = document.getId(); verifiedUid = profileUid;
        verifiedAt = android.os.SystemClock.elapsedRealtime(); tokenExpiresAt = expiresAt;
        com.example.medibook.local.LocalPatientServices.verifiedBestEffort(profileUid, result.user.getRole());
        publish(new State(false, result.user, Error.NONE));
    }

    private void rollbackNewAccount(FirebaseUser user, int operation) {
        // Auth and Firestore cannot share a transaction. Only delete the NEW account whose
        // profile write was rejected. Never delete an existing account on a login/read failure.
        changingAccount = true; // This intentional account removal must not invalidate its callback.
        user.delete().addOnCompleteListener(deleteTask -> {
            if (operation != operationId) return;
            changingAccount = false;
            fail(operation, deleteTask.isSuccessful()
                    ? Error.PROFILE_FAILED_ACCOUNT_REMOVED : Error.PROFILE_FAILED_ACCOUNT_REMAINS);
        });
    }

    private void fail(int operation, Error error) {
        if (operation != operationId) return;
        if (checkingExisting && error == Error.INVALID_CREDENTIALS) error = Error.SESSION_EXPIRED;
        android.util.Log.w("MediBookAuth", "Authentication/session failure: " + error.name());
        invalidateVerifiedSession();
        profileUid = null; checkingUid = null;
        com.example.medibook.local.LocalPatientServices.signOutBestEffort();
        auth.signOut();
        observedUid = currentUid(); changingAccount = false;
        checkingExisting = false;
        publish(new State(false, null, error));
    }

    private void publish(State newState) {
        state = newState;
        for (Listener listener : new ArrayList<>(listeners)) {
            listener.onAuthStateChanged(newState);
        }
    }

    private Error mapError(Exception exception) {
        com.example.medibook.utils.Diagnostics.failure("Authentication request", exception);
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
