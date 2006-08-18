/*************************************************************************
 *                                                                       *
 *  EJBCA: The OpenSource Certificate Authority                          *
 *                                                                       *
 *  This software is free software; you can redistribute it and/or       *
 *  modify it under the terms of the GNU Lesser General Public           *
 *  License as published by the Free Software Foundation; either         *
 *  version 2.1 of the License, or any later version.                    *
 *                                                                       *
 *  See terms of license at gnu.org.                                     *
 *                                                                       *
 *************************************************************************/
 
package org.ejbca.ui.web.pub;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.rmi.RemoteException;
import java.security.cert.X509Certificate;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Set;

import javax.ejb.CreateException;
import javax.ejb.ObjectNotFoundException;
import javax.naming.InitialContext;
import javax.rmi.PortableRemoteObject;
import javax.servlet.ServletConfig;
import javax.servlet.ServletContext;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.apache.log4j.Logger;
import org.ejbca.core.ejb.ca.caadmin.ICAAdminSessionHome;
import org.ejbca.core.ejb.ca.caadmin.ICAAdminSessionRemote;
import org.ejbca.core.ejb.ca.sign.ISignSessionHome;
import org.ejbca.core.ejb.ca.sign.ISignSessionRemote;
import org.ejbca.core.ejb.ca.store.ICertificateStoreSessionHome;
import org.ejbca.core.ejb.ca.store.ICertificateStoreSessionRemote;
import org.ejbca.core.ejb.hardtoken.IHardTokenSessionHome;
import org.ejbca.core.ejb.ra.IUserAdminSessionHome;
import org.ejbca.core.ejb.ra.IUserAdminSessionRemote;
import org.ejbca.core.model.SecConst;
import org.ejbca.core.model.ca.AuthLoginException;
import org.ejbca.core.model.ca.AuthStatusException;
import org.ejbca.core.model.ca.IllegalKeyException;
import org.ejbca.core.model.ca.SignRequestException;
import org.ejbca.core.model.ca.SignRequestSignatureException;
import org.ejbca.core.model.ca.caadmin.CAInfo;
import org.ejbca.core.model.ca.crl.RevokedCertInfo;
import org.ejbca.core.model.hardtoken.profiles.EIDProfile;
import org.ejbca.core.model.hardtoken.profiles.HardTokenProfile;
import org.ejbca.core.model.hardtoken.profiles.SwedishEIDProfile;
import org.ejbca.core.model.log.Admin;
import org.ejbca.core.model.ra.UserDataConstants;
import org.ejbca.core.model.ra.UserDataVO;
import org.ejbca.core.protocol.IResponseMessage;
import org.ejbca.core.protocol.PKCS10RequestMessage;
import org.ejbca.ui.web.RequestHelper;
import org.ejbca.util.Base64;
import org.ejbca.util.CertTools;


/**
 * Servlet used to install a private key with a corresponding certificate in a browser. A new
 * certificate is installed in the browser in following steps:<br>
 * 1. The key pair is generated by the browser. <br>
 * 2. The public part is sent to the servlet in a POST together with user info ("pkcs10|keygen",
 * "inst", "user", "password"). For internet explorer the public key is sent as a PKCS10
 * certificate request. <br>
 * 3. The new certificate is created by calling the RSASignSession session bean. <br>
 * 4. A page containing the new certificate and a script that installs it is returned to the
 * browser. <br>
 * 
 * <p></p>
 * 
 * <p>
 * The following initiation parameters are needed by this servlet: <br>
 * "responseTemplate" file that defines the response to the user (IE). It should have one line
 * with the text "cert =". This line is replaced with the new certificate. "keyStorePass".
 * Password needed to load the key-store. If this parameter is none existing it is assumed that no
 * password is needed. The path could be absolute or relative.<br>
 * </p>
 *
 * @author Original code by Lars Silv�n
 * @version $Id: CardCertReqServlet.java,v 1.11 2006-08-18 10:48:46 primelars Exp $
 */
public class CardCertReqServlet extends HttpServlet {
	private final static Logger log = Logger.getLogger(CardCertReqServlet.class);
    private ISignSessionHome signsessionhome = null;
    private IUserAdminSessionHome useradminhome = null;
    private ICertificateStoreSessionHome certificatestorehome = null;
    private ICAAdminSessionHome caadminsessionhome = null;
    private IHardTokenSessionHome tokenSessionHome = null;

    /**
     * Servlet init
     *
     * @param config servlet configuration
     *
     * @throws ServletException on error
     */
    public void init(ServletConfig config) throws ServletException {
        super.init(config);

        try {
            // Install BouncyCastle provider
            CertTools.installBCProvider();

            // Get EJB context and home interfaces
            InitialContext ctx = new InitialContext();
            signsessionhome = (ISignSessionHome) PortableRemoteObject.narrow(
                      ctx.lookup("RSASignSession"), ISignSessionHome.class );
            useradminhome = (IUserAdminSessionHome) PortableRemoteObject.narrow(
                             ctx.lookup("UserAdminSession"), IUserAdminSessionHome.class );
            certificatestorehome = (ICertificateStoreSessionHome) PortableRemoteObject.narrow(
                    ctx.lookup("CertificateStoreSession"), ICertificateStoreSessionHome.class );
            caadminsessionhome = (ICAAdminSessionHome) javax.rmi.PortableRemoteObject.narrow(ctx.lookup("CAAdminSession"),
                                                                                             ICAAdminSessionHome.class);
            tokenSessionHome = (IHardTokenSessionHome)javax.rmi.PortableRemoteObject.narrow(ctx.lookup("HardTokenSession"),
                                                                                            IHardTokenSessionHome.class);
        } catch( Exception e ) {
            throw new ServletException(e);
        }
    }

    /**
     * Handles HTTP POST
     *
     * @param request servlet request
     * @param response servlet response
     *
     * @throws IOException input/output error
     * @throws ServletException on error
     */
    public void doPost(HttpServletRequest request, HttpServletResponse response)
    throws IOException, ServletException {
        final ServletDebug debug = new ServletDebug(request, response);
        boolean usekeyrecovery = false;
        try {
            Admin administrator = new Admin(Admin.TYPE_RACOMMANDLINE_USER);
            ICertificateStoreSessionRemote certificatestoresession = certificatestorehome.create();
            final String username; {
                Object o = request.getAttribute("javax.servlet.request.X509Certificate");
                final X509Certificate[] certs;
                if ( o!=null && o instanceof X509Certificate[] )
                    certs = (X509Certificate[])o;
                else
                    throw new AuthLoginException("No authenicating certificate");
                RevokedCertInfo rci=certificatestoresession.isRevoked(administrator, certs[0].getIssuerDN().getName(),
                                                                      certs[0].getSerialNumber());
                if ( rci==null || rci.getReason()!=RevokedCertInfo.NOT_REVOKED )
                    throw new UserCertificateRevokedException(certs[0]);
                username = certificatestoresession.findUsernameByCertSerno(administrator,
                        certs[0].getSerialNumber(), certs[0].getIssuerX500Principal().toString());
                if ( username==null || username.length()==0 )
                    throw new ObjectNotFoundException("Not possible to retrieve user name");
            }
            IUserAdminSessionRemote adminsession = useradminhome.create();
            ISignSessionRemote signsession = signsessionhome.create();
            log.debug("Got request for " + username + ".");
            debug.print("<h3>username: " + username + "</h3>");
            
            final UserDataVO data = adminsession.findUser(administrator, username);
            final X509Certificate notRevokedCerts[]; {
                Set set = new HashSet();
                for( Iterator i = certificatestoresession.findCertificatesByUsername(administrator, username).iterator(); i.hasNext(); ) {
                    Object o = i.next();
                    if ( o instanceof X509Certificate ) {
                        X509Certificate cert = (X509Certificate)o;
                        RevokedCertInfo rci=certificatestoresession.isRevoked(administrator, cert.getIssuerDN().getName(), cert.getSerialNumber());
                        if ( rci!=null && rci.getReason()==RevokedCertInfo.NOT_REVOKED )
                            set.add(cert);
                    }
                }
                notRevokedCerts = (X509Certificate[])set.toArray(new X509Certificate[0]);
            }
            if (data == null)
                throw new ObjectNotFoundException();
            
            final String authReq = request.getParameter("authpkcs10");
            final String signReq = request.getParameter("signpkcs10");
            
            if ( authReq!=null && signReq!=null ) {
                final int authCertProfile;
                final int signCertProfile;
                final HardTokenProfile hardTokenProfile = tokenSessionHome.create().getHardTokenProfile(administrator, data.getTokenType());
                {
                    CertProfileID certProfileID = new CertProfileID(certificatestoresession, data, administrator,
                                                                    hardTokenProfile);
                    authCertProfile = certProfileID.getProfileID("authCertProfile", SwedishEIDProfile.CERTUSAGE_AUTHENC);
                    signCertProfile = certProfileID.getProfileID("signCertProfile", SwedishEIDProfile.CERTUSAGE_SIGN);
                }
                final int authCA;
                final int signCA;
                {
                    CAID caid = new CAID(data,administrator, hardTokenProfile);
                    authCA = caid.getProfileID("authCA", SwedishEIDProfile.CERTUSAGE_AUTHENC);
                    signCA = caid.getProfileID("signCA", SwedishEIDProfile.CERTUSAGE_SIGN);
                }
                // if not IE, check if it's manual request
                final byte[] authReqBytes = authReq.getBytes();
                final byte[] signReqBytes = signReq.getBytes();
                if ( authReqBytes!=null && signReqBytes!=null) {
                    adminsession.changeUser(administrator, username,data.getPassword(), data.getDN(), data.getSubjectAltName(),
                                            data.getEmail(), true, data.getEndEntityProfileId(), authCertProfile, data.getType(),
                                            SecConst.TOKEN_SOFT_BROWSERGEN, 0, data.getStatus(), authCA);
                    final byte[] authb64cert=pkcs10CertRequest(administrator, signsession, authReqBytes, username, data.getPassword());

                    adminsession.changeUser(administrator, username, data.getPassword(), data.getDN(), data.getSubjectAltName(),
                                            data.getEmail(), true, data.getEndEntityProfileId(), signCertProfile, data.getType(),
                                            SecConst.TOKEN_SOFT_BROWSERGEN, 0, UserDataConstants.STATUS_NEW, signCA);
                    final byte[] signb64cert=pkcs10CertRequest(administrator, signsession, signReqBytes, username, data.getPassword());

                    data.setStatus(UserDataConstants.STATUS_GENERATED);
                    adminsession.changeUser(administrator, data, true); // set back to original values

                    for (int i=0; i<notRevokedCerts.length; i++)
                        adminsession.revokeCert(administrator, notRevokedCerts[i].getSerialNumber(),
                                                notRevokedCerts[i].getIssuerDN().toString(), username,
                                                RevokedCertInfo.REVOKATION_REASON_SUPERSEDED);

                    sendCertificates(authb64cert, signb64cert, response,  getServletContext(),
                                     getInitParameter("responseTemplate"), notRevokedCerts);
                }
            }
        } catch( UserCertificateRevokedException e) {
            log.error("An error revoking certificaates occured: ", e);
            debug.printMessage(e.getMessage());
            debug.printDebugInfo();
            return;
        } catch (ObjectNotFoundException oe) {
            log.error("Non existent username!", oe);
            debug.printMessage("Non existent username!");
            debug.printDebugInfo();
            return;
        } catch (AuthStatusException ase) {
            log.error("Wrong user status!", ase);
            debug.printMessage("Wrong user status!");
            if (usekeyrecovery) {
                debug.printMessage(
                "To generate a certificate for a user the user must have status new, failed or inprocess.");
            } else {
                debug.printMessage(
                "To generate a certificate for a user the user must have status new, failed or inprocess.");
            }
            debug.printDebugInfo();
            return;
        } catch (AuthLoginException ale) {
            log.error("Wrong password for user!", ale);
            debug.printMessage("Wrong username or password!");
            debug.printDebugInfo();
            return;
        } catch (SignRequestException re) {
            log.error("Invalid request!", re);
            debug.printMessage("Invalid request!");
            debug.printMessage("Please supply a correct request.");
            debug.printDebugInfo();
            return;
        } catch (SignRequestSignatureException se) {
            log.error("Invalid signature on certificate request!", se);
            debug.printMessage("Invalid signature on certificate request!");
            debug.printMessage("Please supply a correctly signed request.");
            debug.printDebugInfo();
            return;
        } catch (java.lang.ArrayIndexOutOfBoundsException ae) {
            log.error("Empty or invalid request received.", ae);
            debug.printMessage("Empty or invalid request!");
            debug.printMessage("Please supply a correct request.");
            debug.printDebugInfo();
            return;
        } catch (IllegalKeyException e) {
            log.error("Illegal Key received: ", e);
            debug.printMessage("Invalid Key in request: "+e.getMessage());
            debug.printMessage("Please supply a correct request.");
            debug.printDebugInfo();
            return;
        } catch (Exception e) {
            log.error("Exception occured: ", e);
            debug.print("<h3>parameter name and values: </h3>");
            Enumeration paramNames = request.getParameterNames();
            while (paramNames.hasMoreElements()) {
                String name = paramNames.nextElement().toString();
                String parameter = request.getParameter(name);
                debug.print("<h4>" + name + ":</h4>" + parameter + "<br>");
            }
            debug.takeCareOfException(e);
            debug.printDebugInfo();
        }
    } //doPost

    private class UserCertificateRevokedException extends Exception {
        UserCertificateRevokedException(X509Certificate cert) {
            super("User certificate with serial number "+cert.getSerialNumber() +
                  " from issuer \'"+cert.getIssuerX500Principal()+"\' is revoked.");
        }
    }
    private class CAID extends BaseID {
        final private ICAAdminSessionRemote caadminsession;
        CAID(UserDataVO d, Admin a, HardTokenProfile hardTokenProfile) throws RemoteException, CreateException {
            super(d, a, hardTokenProfile);
            caadminsession = caadminsessionhome.create();                       
        }
        protected int getFromName(String name) throws RemoteException {
            CAInfo caInfo = caadminsession.getCAInfo(administrator, name);
            if ( caInfo!=null )
                return caInfo.getCAId();
            else
                return 0;
        }
        protected int getFromOldData() {
            return data.getCAId();
        }
        protected int getFromHardToken(int keyType) {
            final int id = hardTokenProfile.getCAId(keyType);
            if ( id!=EIDProfile.CAID_USEUSERDEFINED )
                return id;
            else
                return data.getCAId();
        }
    }
    private class CertProfileID extends BaseID {
        final ICertificateStoreSessionRemote certificatestoresession;
        CertProfileID(ICertificateStoreSessionRemote c, UserDataVO d, Admin a,
                      HardTokenProfile hardTokenProfile) throws RemoteException, CreateException {
            super(d, a, hardTokenProfile);
            certificatestoresession = c;
        }
        protected int getFromName(String name) throws RemoteException {
            return certificatestoresession.getCertificateProfileId(administrator, name);
        }
        protected int getFromOldData() {
            return data.getCertificateProfileId();
        }
        protected int getFromHardToken(int keyType) {
            return hardTokenProfile.getCertificateProfileId(keyType);
        }
    }
    private abstract class BaseID {
        final UserDataVO data;
        final Admin administrator;
        final EIDProfile hardTokenProfile;
        
        protected abstract int getFromHardToken(int keyType);
        protected abstract int getFromName(String name) throws RemoteException;
        protected abstract int getFromOldData();
        BaseID(UserDataVO d, Admin a, HardTokenProfile htp) {
            data = d;
            administrator = a;
            if ( htp!=null && htp instanceof EIDProfile )
                hardTokenProfile = (EIDProfile)htp;
            else
                hardTokenProfile = null;
        }
        public int getProfileID(String parameterName, int keyType) throws RemoteException {
            if ( hardTokenProfile!=null )
                return getFromHardToken(keyType);
            String name = CardCertReqServlet.this.getInitParameter(parameterName);
            if ( name!=null && name.length()>0 ) {
                final int id = getFromName(name);
                log.debug("parameter name "+parameterName+" has ID "+id);
                if (id!=0)
                    return id;
            }
            return getFromOldData();
        }
    }
    /**
     * Handles HTTP GET
     *
     * @param request servlet request
     * @param response servlet response
     *
     * @throws IOException input/output error
     * @throws ServletException on error
     */
    public void doGet(HttpServletRequest request, HttpServletResponse response)
        throws IOException, ServletException {
        log.debug(">doGet()");
        response.setHeader("Allow", "POST");

        ServletDebug debug = new ServletDebug(request, response);
        debug.print("The certificate request servlet only handles POST method.");
        debug.printDebugInfo();
        log.debug("<doGet()");
    }

    // doGet
    
    
    /**
     * Reads template and inserts cert to send back to netid for installation of cert
     *
     * @param b64cert cert to be installed in netid
     * @param response utput stream to send to
     * @param sc serveltcontext
     * @param responseTemplate path to responseTemplate
     * @param notRevokedCerts 
     * @param classid replace
     *
     * @throws Exception on error
     */
    private static void sendCertificates(byte[] authb64cert,byte[] signb64cert, HttpServletResponse response, ServletContext sc,
        String responseTemplate, X509Certificate[] notRevokedCerts) throws Exception {
        if (authb64cert.length == 0 || signb64cert.length == 0) {
            log.error("0 length certificate can not be sent to  client!");
            return;
        }
        StringWriter sw = new StringWriter();
        {
            BufferedReader br = new BufferedReader(new InputStreamReader(sc.getResourceAsStream(responseTemplate)));
            PrintWriter pw = new PrintWriter(sw);
            while (true) {
                String line = br.readLine();
                if (line == null)
                    break;
                line = line.replaceAll("TAG_authb64cert",new String(authb64cert));
                line = line.replaceAll("TAG_signb64cert",new String(signb64cert));
                if ( notRevokedCerts.length > 0 )
                    line = line.replaceAll("TAG_certToRemove1",new String(Base64.encode(notRevokedCerts[0].getEncoded(),false)));
                if ( notRevokedCerts.length > 1 )
                    line = line.replaceAll("TAG_certToRemove2",new String(Base64.encode(notRevokedCerts[1].getEncoded(),false)));
                if ( notRevokedCerts.length > 2 )
                    line = line.replaceAll("TAG_certToRemove3",new String(Base64.encode(notRevokedCerts[2].getEncoded(),false)));
                if ( notRevokedCerts.length > 3 )
                    line = line.replaceAll("TAG_certToRemove4",new String(Base64.encode(notRevokedCerts[3].getEncoded(),false)));
                pw.println(line);
            }
            pw.close();
            sw.flush();
        }
        {
            OutputStream out = response.getOutputStream();
            PrintWriter pw = new PrintWriter(out);
            log.debug(sw);
            pw.print(sw);
            pw.close();
            out.flush();
        }
    } // sendCertificates
    
    /**
     * Handles PKCS10 certificate request, these are constructed as: <code> CertificationRequest
     * ::= SEQUENCE { certificationRequestInfo  CertificationRequestInfo, signatureAlgorithm
     * AlgorithmIdentifier{{ SignatureAlgorithms }}, signature                       BIT STRING }
     * CertificationRequestInfo ::= SEQUENCE { version             INTEGER { v1(0) } (v1,...),
     * subject             Name, subjectPKInfo   SubjectPublicKeyInfo{{ PKInfoAlgorithms }},
     * attributes          [0] Attributes{{ CRIAttributes }}} SubjectPublicKeyInfo { ALGORITHM :
     * IOSet} ::= SEQUENCE { algorithm           AlgorithmIdentifier {{IOSet}}, subjectPublicKey
     * BIT STRING }</code> PublicKey's encoded-format has to be RSA X.509.
     *
     * @param signsession signsession to get certificate from
     * @param b64Encoded base64 encoded pkcs10 request message
     * @param username username of requesting user
     * @param password password of requesting user
     * @param resulttype should indicate if a PKCS7 or just the certificate is wanted.
     *
     * @return Base64 encoded byte[] 
     */
    private byte[] pkcs10CertRequest(Admin administrator, ISignSessionRemote signsession, byte[] b64Encoded,
        String username, String password) throws Exception {
        byte[] result = null;	
        X509Certificate cert=null;
		PKCS10RequestMessage req = RequestHelper.genPKCS10RequestMessageFromPEM(b64Encoded);
		req.setUsername(username);
        req.setPassword(password);
        IResponseMessage resp = signsession.createCertificate(administrator,req,Class.forName("org.ejbca.core.protocol.X509ResponseMessage"));
        cert = CertTools.getCertfromByteArray(resp.getResponseMessage());
          result = cert.getEncoded();

        return Base64.encode(result, false);
    } //pkcs10CertReq
}


// CertReqServlet
