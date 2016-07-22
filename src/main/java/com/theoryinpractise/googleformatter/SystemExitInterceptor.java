package com.theoryinpractise.googleformatter;

import java.security.Permission;
import java.util.concurrent.atomic.AtomicInteger;

// XXX: Absolutely terrible hack.
final class SystemExitInterceptor {
  private static final AtomicInteger ACTIVE_INVOCATIONS = new AtomicInteger();

  static void invoke(final Runnable action) {
    try {
      synchronized (ACTIVE_INVOCATIONS) {
        if (ACTIVE_INVOCATIONS.getAndIncrement() == 0) {
          System.setSecurityManager(
              new SecurityManager() {
                @Override
                public void checkPermission(Permission permission) {
                  if (permission.getName().contains("exitVM")) {
                    throw new ExitNotAllowedException();
                  }
                }
              });
        }
      }
      try {
        action.run();
      } catch (final ExitNotAllowedException e) {
        /* Ignore: don't exit the VM. */
      }
    } finally {
      synchronized (ACTIVE_INVOCATIONS) {
        if (ACTIVE_INVOCATIONS.decrementAndGet() == 0) {
          System.setSecurityManager(null);
        }
      }
    }
  }

  private static final class ExitNotAllowedException extends SecurityException {}
}
