package org.hsqldb.test;

// nbazin@users - enhancements to the original code
// fredt@users - 20050202 - corrected getRandomID(int) to return a randomly distributed value
/*
 *  This is a sample implementation of the Transaction Processing Performance
 *  Council Benchmark B coded in Java and ANSI SQL2.
 *
 *  This version is using one connection per thread to parallellize
 *  server operations.
 * @author Mark Matthews (mark@mysql.com)
 */
import java.io.FileOutputStream;
import java.io.PrintStream;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Enumeration;
import java.util.Vector;

class JDBCBench {

    /* tpc bm b scaling rules */
    public static int tps       = 1;         /* the tps scaling factor: here it is 1 */
    public static int nbranches = 1;         /* number of branches in 1 tps db       */
    public static int ntellers  = 10;        /* number of tellers in  1 tps db       */
    public static int naccounts = 100000;    /* number of accounts in 1 tps db       */
    public static int nhistory = 864000;     /* number of history recs in 1 tps db   */
    public static final int TELLER              = 0;
    public static final int BRANCH              = 1;
    public static final int ACCOUNT             = 2;
    int                     failed_transactions = 0;
    int                     transaction_count   = 0;
    static int              n_clients           = 10;
    static int              n_txn_per_client    = 10;
    long                    start_time          = 0;
    static boolean          transactions        = true;
    static boolean          prepared_stmt       = false;
    static String           tableExtension      = "";
    static String           createExtension     = "";
    static String           ShutdownCommand     = "";
    static String           startupCommand      = "";
    static PrintStream      TabFile             = null;
    static boolean          verbose             = false;
    MemoryWatcherThread     MemoryWatcher;

    /* main program,    creates a 1-tps database:  i.e. 1 branch, 10 tellers,...
     *                    runs one TPC BM B transaction
     * example command line:
     * -driver  org.hsqldb.jdbcDriver -url jdbc:hsqldb:/hsql/jdbcbench/test -user sa -clients 20 -tpc 10000
     */
    public static void main(String[] Args) {

        String  DriverName         = "";
        String  DBUrl              = "";
        String  DBUser             = "";
        String  DBPassword         = "";
        boolean initialize_dataset = false;

        for (int i = 0; i < Args.length; i++) {
            if (Args[i].equals("-clients")) {
                if (i + 1 < Args.length) {
                    i++;

                    n_clients = Integer.parseInt(Args[i]);
                }
            } else if (Args[i].equals("-driver")) {
                if (i + 1 < Args.length) {
                    i++;

                    DriverName = Args[i];

                    if (DriverName.equals(
                            "org.enhydra.instantdb.jdbc.idbDriver")) {
                        ShutdownCommand = "SHUTDOWN";
                    }

                    if (DriverName.equals(
                            "com.borland.datastore.jdbc.DataStoreDriver")) {}

                    if (DriverName.equals("com.mckoi.JDBCDriver")) {
                        ShutdownCommand = "SHUTDOWN";
                    }

                    if (DriverName.equals("org.hsqldb.jdbcDriver")) {
                        tableExtension  = "CREATE CACHED TABLE ";
                        ShutdownCommand = "SHUTDOWN";
                        startupCommand  = "";
                    }
                }
            } else if (Args[i].equals("-url")) {
                if (i + 1 < Args.length) {
                    i++;

                    DBUrl = Args[i];
                }
            } else if (Args[i].equals("-user")) {
                if (i + 1 < Args.length) {
                    i++;

                    DBUser = Args[i];
                }
            } else if (Args[i].equals("-tabfile")) {
                if (i + 1 < Args.length) {
                    i++;

                    try {
                        FileOutputStream File = new FileOutputStream(Args[i]);

                        TabFile = new PrintStream(File);
                    } catch (Exception e) {
                        TabFile = null;
                    }
                }
            } else if (Args[i].equals("-password")) {
                if (i + 1 < Args.length) {
                    i++;

                    DBPassword = Args[i];
                }
            } else if (Args[i].equals("-tpc")) {
                if (i + 1 < Args.length) {
                    i++;

                    n_txn_per_client = Integer.parseInt(Args[i]);
                }
            } else if (Args[i].equals("-init")) {
                initialize_dataset = true;
            } else if (Args[i].equals("-tps")) {
                if (i + 1 < Args.length) {
                    i++;

                    tps = Integer.parseInt(Args[i]);
                }
            } else if (Args[i].equals("-v")) {
                verbose = true;
            }
        }

        if (DriverName.length() == 0 || DBUrl.length() == 0) {
            System.out.println(
                "usage: java JDBCBench -driver [driver_class_name] -url [url_to_db] -user [username] -password [password] [-v] [-init] [-tpc n] [-clients n]");
            System.out.println();
            System.out.println("-v          verbose error messages");
            System.out.println("-init       initialize the tables");
            System.out.println("-tpc        transactions per client");
            System.out.println("-clients    number of simultaneous clients");
            System.exit(-1);
        }

        System.out.println(
            "*********************************************************");
        System.out.println(
            "* JDBCBench v1.1                                        *");
        System.out.println(
            "*********************************************************");
        System.out.println();
        System.out.println("Driver: " + DriverName);
        System.out.println("URL:" + DBUrl);
        System.out.println();
        System.out.println("Scale factor value: " + tps);
        System.out.println("Number of clients: " + n_clients);
        System.out.println("Number of transactions per client: "
                           + n_txn_per_client);
        System.out.println();

        try {
            Class.forName(DriverName);

            JDBCBench Me = new JDBCBench(DBUrl, DBUser, DBPassword,
                                         initialize_dataset);
        } catch (Exception E) {
            System.out.println(E.getMessage());
            E.printStackTrace();
        }
    }

    public JDBCBench(String url, String user, String password, boolean init) {

        Vector      vClient  = new Vector();
        Thread      Client   = null;
        Enumeration e        = null;
        Connection  guardian = null;

        try {
            java.util.Date start = new java.util.Date();

            if (init) {
                System.out.println("Start: " + start.toString());
                System.out.print("Initializing dataset...");
                createDatabase(url, user, password);

                double seconds = (System.currentTimeMillis() - start.getTime())
                                 / 1000D;

                System.out.println("done. in " + seconds + " seconds\n");
                System.out.println("Complete: "
                                   + (new java.util.Date()).toString());
            }

            guardian = connect(url, user, password);

            if (startupCommand.length() != 0) {
                Statement statement = guardian.createStatement();

                statement.execute(startupCommand);
                statement.close();
            }

            System.out.println("* Starting Benchmark Run *");

            MemoryWatcher = new MemoryWatcherThread();

            MemoryWatcher.start();

            transactions  = true;
            prepared_stmt = false;
            start_time    = System.currentTimeMillis();

            for (int i = 0; i < n_clients; i++) {
                Client = new ClientThread(n_txn_per_client, url, user,
                                          password);

                Client.start();
                vClient.addElement(Client);
            }

            /*
             ** Barrier to complete this test session
             */
            e = vClient.elements();

            while (e.hasMoreElements()) {
                Client = (Thread) e.nextElement();

                Client.join();
            }

            vClient.removeAllElements();
            reportDone();
            checkSums(guardian);

            // debug - allows stopping the test
            if (!transactions) {
                throw new Exception("end after one round");
            }

            transactions  = true;
            prepared_stmt = false;
            start_time    = System.currentTimeMillis();

            for (int i = 0; i < n_clients; i++) {
                Client = new ClientThread(n_txn_per_client, url, user,
                                          password);

                Client.start();
                vClient.addElement(Client);
            }

            /*
             ** Barrier to complete this test session
             */
            e = vClient.elements();

            while (e.hasMoreElements()) {
                Client = (Thread) e.nextElement();

                Client.join();
            }

            vClient.removeAllElements();
            reportDone();
            checkSums(guardian);

            transactions  = true;
            prepared_stmt = true;
            start_time    = System.currentTimeMillis();

            for (int i = 0; i < n_clients; i++) {
                Client = new ClientThread(n_txn_per_client, url, user,
                                          password);

                Client.start();
                vClient.addElement(Client);
            }

            /*
             ** Barrier to complete this test session
             */
            e = vClient.elements();

            while (e.hasMoreElements()) {
                Client = (Thread) e.nextElement();

                Client.join();
            }

            vClient.removeAllElements();
            reportDone();
            checkSums(guardian);

            transactions  = true;
            prepared_stmt = true;
            start_time    = System.currentTimeMillis();

            for (int i = 0; i < n_clients; i++) {
                Client = new ClientThread(n_txn_per_client, url, user,
                                          password);

                Client.start();
                vClient.addElement(Client);
            }

            /*
             ** Barrier to complete this test session
             */
            e = vClient.elements();

            while (e.hasMoreElements()) {
                Client = (Thread) e.nextElement();

                Client.join();
            }

            vClient.removeAllElements();
            reportDone();
            checkSums(guardian);
        } catch (Exception E) {
            System.out.println(E.getMessage());
            E.printStackTrace();
        } finally {
            MemoryWatcher.end();

            try {
                MemoryWatcher.join();

                if (ShutdownCommand.length() > 0) {
                    Statement Stmt = guardian.createStatement();

                    Stmt.execute(ShutdownCommand);
                    Stmt.close();
                    connectClose(guardian);
                }

                if (TabFile != null) {
                    TabFile.close();
                }
            } catch (Exception E1) {}

//            System.exit(0);
        }
    }

    public void reportDone() {

        long end_time = System.currentTimeMillis();
        double completion_time = ((double) end_time - (double) start_time)
                                 / 1000;

        if (TabFile != null) {
            TabFile.print(tps + ";" + n_clients + ";" + n_txn_per_client
                          + ";");
        }

        System.out.println("\n* Benchmark Report *");
        System.out.print("* Featuring ");

        if (prepared_stmt) {
            System.out.print("<prepared statements> ");

            if (TabFile != null) {
                TabFile.print("<prepared statements>;");
            }
        } else {
            System.out.print("<direct queries> ");

            if (TabFile != null) {
                TabFile.print("<direct queries>;");
            }
        }

        if (transactions) {
            System.out.print("<transactions> ");

            if (TabFile != null) {
                TabFile.print("<transactions>;");
            }
        } else {
            System.out.print("<auto-commit> ");

            if (TabFile != null) {
                TabFile.print("<auto-commit>;");
            }
        }

        System.out.println("\n--------------------");
        System.out.println("Time to execute " + transaction_count
                           + " transactions: " + completion_time
                           + " seconds.");
        System.out.println("Max/Min memory usage: " + MemoryWatcher.max
                           + " / " + MemoryWatcher.min + " kb");
        System.out.println(failed_transactions + " / " + transaction_count
                           + " failed to complete.");

        double rate = (transaction_count - failed_transactions)
                      / completion_time;

        System.out.println("Transaction rate: " + rate + " txn/sec.");

        if (TabFile != null) {
            TabFile.print(MemoryWatcher.max + ";" + MemoryWatcher.min + ";"
                          + failed_transactions + ";" + rate + "\n");
        }

        transaction_count   = 0;
        failed_transactions = 0;

        MemoryWatcher.reset();
    }

    public synchronized void incrementTransactionCount() {
        transaction_count++;
    }

    public synchronized void incrementFailedTransactionCount() {
        failed_transactions++;
    }

    void createDatabase(String url, String user,
                        String password) throws Exception {

        Connection Conn = connect(url, user, password);
        ;
        String     s    = Conn.getMetaData().getDatabaseProductName();

        System.out.println("DBMS: " + s);

        transactions = true;

        if (transactions) {
            try {
                Conn.setAutoCommit(false);
                System.out.println("In transaction mode");
            } catch (SQLException Etrxn) {
                transactions = false;
            }
        }

        try {
            int       accountsnb = 0;
            Statement Stmt       = Conn.createStatement();
            String    Query;

//
            Stmt.execute("SET WRITE_DELAY 10000 MILLIS;");
            Stmt.execute("SET PROPERTY \"hsqldb.cache_scale\" 16;");

//
            Query = "SELECT count(*) ";
            Query += "FROM   accounts";

            ResultSet RS = Stmt.executeQuery(Query);

            Stmt.clearWarnings();

            while (RS.next()) {
                accountsnb = RS.getInt(1);
            }

            if (transactions) {
                Conn.commit();
            }

            Stmt.close();

            if (accountsnb == (naccounts * tps)) {
                System.out.println("Already initialized");
                connectClose(Conn);

                return;
            }
        } catch (Exception E) {}

        System.out.println("Drop old tables if they exist");

        try {
            Statement Stmt = Conn.createStatement();
            String    Query;

            Query = "DROP TABLE history";

            Stmt.execute(Query);
            Stmt.clearWarnings();

            Query = "DROP TABLE accounts";

            Stmt.execute(Query);
            Stmt.clearWarnings();

            Query = "DROP TABLE tellers";

            Stmt.execute(Query);
            Stmt.clearWarnings();��5�}V�M��.�8�z�ue��V�c������R���
�}w'f�' %��Qܿu^�]��o�㱂�/�1���/�jQ��Z��oʑV�6^[�Zde@J�|�ٞ���ٿ�b=��#k�Pż�ńU��V%�@�u�
���oighVG�\�Y��<����s���w��"2��賽jq��+�֖j� �>�֛�D��rZ�H&�lR
w��
������s���7_�
��P��O�\��ƒ|��!�j���8%�3ќ�̫���M�2�8:KY��.�YY�WKk@G���X
,�ސ��2S�D!%d
�8oCZb��d�bPE�)�������s��,�s֟N���p,b�u��'���&�g��N�)�g�������=���GGh��٪�G-O�^I$Pj��ߊcS��B�G�c92sf���>T;	/KF��6�T�5�PY��

�|��'���eZ�􂣜2c�
	
�*��:��&��6��.��>��!��1�	�)��9��%��5�
��'����
���"�M��e��Ő޾O���p14��|��l�ykq�˃����!��xΓ�$̹���|�]:�"��4O������\C��OQWm��m>
�"���e����?��ɸcO|���K�@2�hڷ��v6l�QN��]�aH�FB����$�OC����,T�'�.0��U��1B��b��n�-����Q�rK��x
�����ҨtH�4�z{y��fUQ�������#�P�XY���\a����3��y��`})\��C����bY��Ds��с�mDw�~7H5O�a�S��l��I6Ji�-�NO~VH��*���[*��Ъ�b}���p�0��X�=M�N|Q�iIV���y�~ow��t�M��I��6YF��~^��_</j���c��f��Ts��h���n��woN�����U�l #�����1�<˘����˰��wh[@q+�Obn
�(uI�߿g6�����E�����6�qqn��T��g,���V�a#<�<�q�G�� �ю��*n,	Q���9�����[���v�\Ō]����o�;Lw�^�C���J8h���_�u��6�e�T ��'����b�/��;Ac�٦^��]x^0�`FA�{VN^t�EÈǑ"
�n�s;����=��
%���bo��Qm�/�Gq�Z��XuMt	�C`;�@o��[
Q��5���>�
�8�8&���������^S:͞]`5��� �c*�_���ٝ� &������}����\/O�J�&��ņ��-z5�RQ�?#�3)�d���պV@��K���;���I7���gU��7�4�_O��$3��y�-���4��e���:-��( �*���-�qG��v���yI�i+f�m�sR����]���!x� I5��h���ו����=LL��(���)��B �u�����_}��Ed\�edR����L�j�VGk�bU{	�D��Qd	�"��Q%RO
��>��~�螶�}���|�s_�ā'[�U�Z�k��&;�Tg"&��"��x��щqgI̜-��ԓ�7B��Gސ��<��wss;�K����Z"�m�r]Oꋷ��Ǳ?V\E�}���)��Z�-ӿm���O�U!�z8�n��#�^�yҮlm�����kY��V|����?ϋ:C��������ƴ��ڙ�#R�|�p��(�>��	m��}�˪O�h�6E݈������\yϘ�(����.�K�!�*�X�#f�a"Q��?��.w�"k[:ҸzL���D�LP�5��WʪG)!�)5Yy�
w���H&8����GOW|�r�46L?~~��5	1��nv�r���kR����a�(���I��$;Sw�}J�Hg��ʫ�Q�����re�$kM�}-�:�Ŕ�phӆ������&���G��o��J��H?�Ь�m���� -2Hv�;�f�"_�3���B_x���r)�83��j>�
�t
U�)tu�8m�s��s����k�Y`!�ca%u,�l0��xg��ۛi|��k��������VP<_�/��GCK�y��>{Y�_�3�4,��ԕҾ-z�~*�F�b����� ���zg�һ,W!�ոޟ�=��0}�b����R��r��|�G�3��E����-��]i��;l�#�Y��I�.�j�]s4��$�[
qkޫk`b{�\��"x�q����A�N=Bx�%���j�bC�����Ox�{93�#�(1�@��bb	
ꠊ�g;�Y�YQ�Tm|��
X^C�ݶ���F�]:�^Y���Wq��s�)���3QIQ�˗bߍ3���:򫥷rv���L|_�6Tvw�����?}����Ŏ�[�s�f�G�ѫ�;P����
u�'05���*L�X�1���^%T��&��Yag_�%���7�����ȩg`�;��i�\�_Zr'��G�� �R� '�
ɗ���
��L:`��P�|�A��[�g9��=������/�Y��+�Wq�
����E�$��o�rl�1+D����Q�;8����
#9�l�����֯��hrm���o�
#�=Ho����d���Ҵ�T�V�ǀ�����?O7�ߊ��nO�fӜy��@�!Ѹ����C�i���"-�	�MJ@�ۜQ�0B�k�[�c��˫ȷ�q��N����}M��W���D1'u��9��q�Qc�ղ�)팢�{��l�����L|�����U~��ے%u&p�g5��rA���}"�fy{' ���6�����؇d�������Z��Cѧi����f��q�!uO��Yq��������[HM��?Z�����O�dƅII�%�G	|�ws�=[Yx�/4�{�t�����/֑M�585s֎ݑ�&�q{�^�`��F��&4��ٹ�-(OQ���Z^(R�Mݩ�&�{�C� �46[>�Ϸ��Gr^�u���<��2�}����럯����Lx����W�R�^ 8�O�\_����/��#��5v������{���=mq���?ϓ�U�S����T�KG�F�M��W@mE��%7Sj&s+s�!F��1����+X�mCw2T ��̶�F(=���g2�'n=��(5l�XJ���P4�Ȗ(4z,[I�K����ȍ��Q�^m��:��p����ᅮ�'��4qS�}
����ݟ# �l�o�~F�VԶ]�h:ϲBϠ��.�ٲJ�����rG��^->E>�ZK�����#f���o�ޟ8�F��P��M�æ�y��3���ץ�|k���[Y��ڷ��l�w�{U�B�z�V�G-B�ar���3_�'�s���K���-�O%nOaQ��s�%E��(���*���%�O�5Ew����=�9#��4r?��}�L�O�4��@\=[%����4z0gQ�?��!u"�ts65/�vR'�GS0��2�Av��<N덜��>�W	�[ޢ}�Ow�����<Ž�9�vEF'T60���VMz�y�X~��"���Ƽȸ|�v������]�Ңfz:ϩ�w��}�:�'���0໏�6�Z���]O����� / ����XXpN<N�zٿ[Y�.�5|�O�i�q�~~�l�
9d��q��q�T�:�D�v�򫬀p�Z	�`	�`��p��$��,�,��$hr��)��);��(ņ{�y���AW��L�'�b�Z?j��rSIY&b��6�q�/r`5ez�X���2?���MH��z�WL��\��a��Saa��dp��_�V��!r3���xˑ�m9�9^z����~9K��vOs��MA��[�.{�7cX���Q��۬�ZA^ڍ�Vp��:��Q�J�J�w'3���P��C������a+������<��Q�˛n����	��3 �RzH{Y^���kJR���x�i�וT�u;���7�R�rHC��Q�@Q�vM���O�W�o��(���7uhWع7N���pἾyE9�@J����Һr\�^�ۻ�n����f'{��NHm5lY�!tS#�c���rN@���>�_��
R����9# �|�+�\��^����6�����Q\d&�B������wh�L5�dp��*�X���eBkψ[sS�q_�݀�۾8��
 �jh��Uzm���_RY�������%�����W�!�ʾ~
N��A���y3HϨ"D�i�jD�4�q%;��{Ӣ���h^(�55�$�����s
4b�94��4M�b���h{���h��b�RV��hֶj��PA�V8��F{�s"1x� ^�\`/�͉�$���]d�U���>؎}e�Hyъ��o>��XA��\c�'T_]u[������&'�R�D�[s�X�$
x���h�7��n+};��v������J�Hex:�*�u�P���P�D|J|�����|�26�i����������"��f��P|���$xn8��e����[�\��B��Xn�-�����,�^M����.	ߢ��L�p���qi߰�J�]��^���w��#s��<wZ�+���*,+D>/\}���i�l���@�=q�)t���7�
���Ԋ�S���x�����dt�	�8��Ep�w.H�()H�AĎS����p�\��o��4��WR����@������Ԉ�|�Dd�T��n,������X�6/D$���w�:��*�ى��0< �A���h�~җM0�Hb{Y����5�y
����JKTآN=�,�/	u�;���Ï�������}(⭻ɌWm�oL�}��1��Z��yB5l�b�
���E(aٍ�ҼHKvu�"h�\7G><�Ym�E+�_,���F��Uq>��@$����
5Z�Ȧ�n͸��1�p�y+�t7�03v�}��ڃ7�Lb_|���6.c����w�T8�>K���=����s��}�l��� 
1�i�w��pt`k茶�����,�ab��5r�~�L�遈�1ET���v34/a��&*Nd�7�lKOܹ�i4�1
�1�.���Ӈ�U"�M
. IZ2$^*|L'?�[���Q���(1Ń�ы#`	�P�PHK�R�.�1�7�m*��r�a!�;�W�"wcP䬐��Q:hf�L��o�|� ��Ck'BN�M��C���|4�9���p&2�k�J��R&98�t�6��/�!�u��T�6��}z�UdrK0����+��$�fH�4��")s10���4NCz�2��P]����Oh���3v��nh�P�F�fA�	iA|�g��.�)%��:��a�gof�e9gbo�[�����O�Ѡ
9Y���ҟ�a��d��~]��aMB�/�٫�,L^�Ǝ؜䱎H.��	�{]CdK3��[�_�d�奌[�	WBx+�r���u�j�w0�' ��Rr[�Q�2Wꖲc0��6���0�\����}#a��pFu�����h���}(�b�p�� ���B�PL}��U��nAJ�Ϧ���W[��(�^ǣm�J[��]����^ɤA l�;ZOe�?b�R�Fs�J�)"�΂b�d&�{�3#hy�٠��!��Sm�-�wx�-8���~�"^:hF@bl�&�8����4��n�����&޷ͬ�R=�	z,�Д�����+*=7E{?5

G�EN�p���u��aQ�?z�'t���5VQ���v��%^v��\CL1�8Eʹ/���&���Ɛ�T��t>�@������+{i�<m�XSɤ 4�����-Iz �CK��S�p��-�f柶m۶m۶�m۶m۶m�w��U�u��O�>u�z��13Ɯ��ٙ0�B2$硠���=� h��W:/}����zr���Ʋ/ Sﮄ��������	����T
�.����)��Hx�]�Ĉ5������#�Q��R0u8�)7�8��8��K�6v<���P=�)��ڥPMx��������c�=��h�����mWSY095V��l���-obwP�������NT�=g�
�s�\��F���~�20�s��F����C����6C
r�Y�v��[:���5(%x@p�#��bRB��N����!T�,홣���K� ſ4�(#��������uO�R���)B�I����M�W�����/����Y�I<�����z8��U����7v��F�d�ѕ��k�'�*���%�պA�F���E�J� �k�����4�л���cDvb�s6`ޟ�3�?-_�����Z�M��V��B��a���`eY�Э��L��&U�AI�7$�����H�Y���\=�El����!wpE�Hæ�b�z�b��z���\�o�M�#�@bYA�&���1N?^Oӝ$X~���
t�[<���s��.�U�d��誈;U<��LJ��TEO*�*�5T
4Rc�=����u:jc����V�2����_��BQ����V�v��(�^�C��B^�`�ZV��.G��!�¯���"I�>��)=�r���\����և�! T������
Y4�A\��@O�V$N
�iW5����'B3׽����BA�tEG욯M��lѤ�e�-���s��κ�?�T��-��\��,Aw�6/�����k�~�O��J��CԿ�����S�
	�>ag��Ίt��G���#+�֚
A=Ǫ�D�R\�#M�*���p��=����_��jGgR]���%,�%6�y3��	ۖ�J����~.-�� ��M�	�������)®SK����ڽwu/[H,�\����vA92�a���d� %�%@�%5B/�̐��ObgZ4�}�\1ȃ�,?�-�t�/��̾�m�e��4ޠF��Ք-�5Ӕ{�A�x]����b��t�t� ���F��F�q�H�]@�C�X7���Qߡv��KMVߌ����!V-Q��<���Eģ{X���	�(��:,�.L$�w�ڢ^$�{	'z%��y�ŅU%��H�Sg#Q6S�̊`8���e��.	1���PS�C�U/��/�ggH'E�gC�Ǭ.\/k:T��DU�ώ��
�`�������r��莉?e� 0�($��g0)6��v�g�E��Pll��&!>����%�1��Q<�m������SrD�*��]��ws��|R��9�6v�Y: x���"3�=��d��G��߈��ivl��V7͐ߴS����>�mz�6~Gf�_,,O���v�:*�\�U��7.��7��,�� ++J�ψ�K&��RSl��%gl���Kts�ݮ��Y�z�- �T
Y�m3�^ED�bD:# 3s�^�޴�w�{��8?�2�G4�-�
��;T���n��}�����Q������W*���#�ǆ��-��v�y+�C���-�j-�D؝�ކ��#=m��6�+yH�^��4���k�?`s��1@��gRC��w�Vx�a�
�O��������8�h��=1Y�L�[�u/8��b#�%�S� �Ό��4�w����?9^� |C��k&�5�Q�����\EQ�?�xU5,t�<� yzp�ꄅ��:�jcV����Y���9���b����g�7�XŊS��ԜB��C�۠$���W�Ӟ�i�ߟ2?��>�e�����6�#U	i�[N-\��*Ե���~MUV��-�3'��7Teg\���(��i�E�� fVү2�9G�l̹T�n�"n�5�יX��E���o�;�6������@��afz��w:��!�қO|D�Ư�B�AJ{̄�"43L�f2���X7�����g�U�%�lV��ړ9��ji���A�}�$�<�h���)YLڜ*Y�Ӈ>!s2ӌxV��U��.K�H1ݦ��Tǋ��{-|^.��IO�6٩A���ɢ��
�����* =�jI�w><��̠d`.$�i��(�$΢���b����{��0a��VGґ�!zI��Cs���:gg�<��gFa��SGr�
�^4^ǫѕ��Yt�8;�L�ݷ��.��.o�̱�!���'Q�o����k,����]�F�c���ZT",��ޔ�b -��!�Zfu�)��qc8�W�o(߲����/�3���3�o��!������%�8X���F,Q���Ik����K�K+�gS��AM�+����xOq�	��Dj������׵�材''��w����Dd���&�XC�\0�a*�Fp����p��?�P�:9ϛT�uBW��VB?� E�w�We���[�?��5����2�P��T�ї���7�[���×SR�]S��7�o>��ɻ)1��Q�Q��*Z4�����G��3>}�%]�'\"N#sR����*ҹD��
P��eK5�--H26D�s��2�� k��.�P�h1�Z/cF�˓��^ n����	}�+b�x����45 X`