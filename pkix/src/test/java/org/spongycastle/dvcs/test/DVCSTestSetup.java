
package org.spongycastle.dvcs.test;

import java.security.Security;

import junit.extensions.TestSetup;
import junit.framework.Test;
import org.spongycastle.jce.provider.BouncyCastleProvider;

class DVCSTestSetup
    extends TestSetup
{
    public DVCSTestSetup(Test test)
    {
        super(test);
    }

    protected void setUp()
    {
        Security.addProvider(new BouncyCastleProvider());
    }

    protected void tearDown()
    {
        Security.removeProvider(BouncyCastleProvider.PROVIDER_NAME);
    }

}
