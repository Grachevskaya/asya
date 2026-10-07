package android.app;

import android.os.Binder;
import android.os.RemoteException;

/**
 * The framework interface SystemUI registers itself as, and the carrier the module's bridge rides on.
 *
 * Only the three methods the framework calls are declared here: the module's object answers the
 * interface's own transaction codes itself, so nothing else is needed to compile against it.
 */
public interface IProcessObserver {

    void onForegroundActivitiesChanged(int pid, int uid, boolean foregroundActivities) throws RemoteException;

    void onForegroundServicesChanged(int pid, int uid, int serviceTypes) throws RemoteException;

    void onProcessDied(int pid, int uid) throws RemoteException;

    abstract class Stub extends Binder implements IProcessObserver {

    }
}
