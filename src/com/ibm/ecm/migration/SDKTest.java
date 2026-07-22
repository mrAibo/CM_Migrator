/*
 * Projekt: CM Migrator 2.2.1.
 * @Author: Aleksej Voronin, Sven Lindt
 * @Date:   26.01.2026
 */
package com.ibm.ecm.migration;

import com.ibm.mm.sdk.common.DKDDO;
import java.lang.reflect.Method;

// Existieren die Benötigten SDK-Dateien?
// 
public class SDKTest {
    public static void main(String[] args) {
        try {
            Method m = DKDDO.class.getMethod("destroy");
            System.out.println("RESULT: DKDDO.destroy() EXISTS");
        } catch (NoSuchMethodException e) {
            System.out.println("RESULT: DKDDO.destroy() DOES NOT EXIST");
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}
