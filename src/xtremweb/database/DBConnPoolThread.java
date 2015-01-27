/*
 * Copyrights     : CNRS
 * Author         : Oleg Lodygensky
 * Acknowledgment : XtremWeb-HEP is based on XtremWeb 1.8.0 by inria : http://www.xtremweb.net/
 * Web            : http://www.xtremweb-hep.org
 * 
 *      This file is part of XtremWeb-HEP.
 *
 *    XtremWeb-HEP is free software: you can redistribute it and/or modify
 *    it under the terms of the GNU General Public License as published by
 *    the Free Software Foundation, either version 3 of the License, or
 *    (at your option) any later version.
 *
 *    XtremWeb-HEP is distributed in the hope that it will be useful,
 *    but WITHOUT ANY WARRANTY; without even the implied warranty of
 *    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *    GNU General Public License for more details.
 *
 *    You should have received a copy of the GNU General Public License
 *    along with XtremWeb-HEP.  If not, see <http://www.gnu.org/licenses/>.
 *
 */

/**
 * DBConnPoolThread.java
 * This class connects to DB and executes query.
 * This class extends java.lang.Thread to execute SQL query in background
 * 
 * Created: Sun Jun 20 2011
 * 
 * @author <a href="mailto:lodygens /at\ lal.in2p3.fr ">Oleg Lodygensky</a>
 * @since 7.5.0
 */

package xtremweb.database;

import java.io.IOException;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Collection;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Vector;

import xtremweb.common.Logger;
import xtremweb.common.MileStone;
import xtremweb.common.Table;
import xtremweb.common.Type;
import xtremweb.common.UID;
import xtremweb.common.WorkInterface;
import xtremweb.common.XWConfigurator;
import xtremweb.common.XWPropertyDefs;
import xtremweb.common.StatusEnum;

/**
 * This is a threaded version of DBConnPool to improve performances This acts as
 * DBConnPool for insert, select and delete This uses a FIFO for update
 * 
 * @author Oleg Lodygensky
 * @since 7.5.0
 */
public class DBConnPoolThread extends Thread {

	/**
	 * This stores the table history suffix name = "_history"
	 * This was in xtremweb.dispatcher.TableRow until 9.0.0
	 * @since 9.0.0
	 */
	public static final String HISTORYSUFFIX = "_history";

	/**
	 * Configurator
	 * @since 9.0.0
	 */
	private  XWConfigurator config;

	private Logger logger;
	/**
	 * This contains the complete reference to the database location
	 */
	private String dburl;

	/**
	 * This contains the number of current connections
	 */
	private int nbConnections;

	/** Maximunm number of connections */
	private int MAXX_CONNECTIONS = 10;
	/**
	 * This is a synchronized list containing connections to database
	 */
	private List<Connection> connPool;
	/**
	 * This is a synchronized list used as a FIFO, containing update requests
	 * 
	 * @since 7.5.0
	 */
	private List<String> updateFifo;

	/**
	 * This is the singleton
	 */
	private static DBConnPoolThread instance = null;

	/**
	 * This contains this class name
	 */
	private String className;

	/**
	 * This is the default and only constructor
	 */
	public DBConnPoolThread(final XWConfigurator c) {

		if (getInstance() != null) {
			return;
		}

		logger = new Logger(this);

		config = c;

		MAXX_CONNECTIONS = config
				.getInt(XWPropertyDefs.DBCONNECTIONS);
		logger.config("MAXX_CONNECTIONS = " + MAXX_CONNECTIONS);
		try {
			dburl = "jdbc:"
					+ config
					.getProperty(XWPropertyDefs.DBVENDOR) + "://"
					+ config.getProperty(XWPropertyDefs.DBHOST)
					+ "/"
					+ config.getProperty(XWPropertyDefs.DBNAME);
			logger.config("org.gjt.mm.mysql.Driver");
			Class.forName("org.gjt.mm.mysql.Driver");
		} catch (final java.lang.ClassNotFoundException e) {
			logger.fatal("ClassNotFoundException: " + e.getMessage());
		}

		className = getClass().getName();

		logger.config("dburl      = '" + dburl + "' " + "dbuser     = '"
				+ config.getProperty(XWPropertyDefs.DBUSER)
				+ "' dbpassword = '"
				+ config.getProperty(XWPropertyDefs.DBPASS) + "'");

		connPool = Collections.synchronizedList(new LinkedList<Connection>());
		updateFifo = Collections.synchronizedList(new LinkedList<String>());

		for (int i = 0; i < MAXX_CONNECTIONS; i++) {

			try {
				final Connection conn = getConnection(dburl,
						config.getProperty(XWPropertyDefs.DBUSER
								.toString()),
								config.getProperty(XWPropertyDefs.DBPASS
										.toString()));
				if (conn != null) {
					pushConnection(conn);
				}
			} catch (final Exception e) {
				logger.fatal(e.toString());
			}
		}

		logger.info("Connection to database " + dburl + " is ok, "
				+ connPool.size() + " created");
		nbConnections = 0;

		checkAppTypes();

		if (getInstance() == null) {
			setInstance(this);
		}
	}

	/**
	 * This creates a new database connector
	 */
	private Connection getConnection(String dbname, String dbuser,
			String dbpassword) throws SQLException {

		return DriverManager.getConnection(dbname, dbuser, dbpassword);
	}

	/**
	 * This waits until nbConnections &lt; MAXX_CONNECTIONS; then this
	 * decrements nbConnections and create a new Connection
	 * 
	 * @see #nbConnections
	 */
	private synchronized Connection popConnection() {

		while (connPool.size() <= 0) {
			try {
				logger.finest("DBConnPool#popConnection sleeping (pool size <= 0)");
				wait();
				logger.finest("DBConnPool#popConnection woken up");
			} catch (final InterruptedException e) {
			}
		}
		final Connection conn = connPool.remove(0);
		notifyAll();
		return conn;
	}

	/**
	 * This increments nbConnections
	 * 
	 * @see #nbConnections
	 */
	private synchronized void pushConnection(Connection conn) {
		nbConnections--;
		if (nbConnections < 0) {
			nbConnections = 0;
		}
		connPool.add(conn);
		notify();
	}

	/**
	 * This is the main loop
	 */
	@Override
	public synchronized void run() {
		while (true) {
			try {
				logger.finest("DBConnPoolThread is waiting");
				this.wait();
				logger.finest("DBConnPoolThread woken up");
			} catch (final InterruptedException e) {
			}
			try {
				while (updateFifo.size() > 0) {
					executeQuery(updateFifo.remove(0), null);
				}
			} catch (final Exception e) {
				logger.exception(e);
			}
		}
	}

	/**
	 * This checks application types defined in DB by scripts:
	 * <ul><li>xwhep-core-tables-create-tables.sql</li></ul> 
	 * and/or
	 * <ul><li>xwhep-core-tables-from-8-create-new-tables-columns-fk.sql</li></ul>
	 * @since 9.0.0
	 */
	private final void checkAppTypes() {
	}

	/**
	 * This executes SQL query
	 * 
	 * @param query
	 *            is the SQL query to execute
	 * @param row
	 *            is the row type
	 * @return a vector of rows found in DB, or null if no row found
	 */
	protected final synchronized <T extends Type> Collection<T> executeQuery(
			String query, T row) throws IOException {
		return executeQuery(null, query, row);
	}

	/**
	 * This executes SQL query
	 * 
	 * @param query
	 *            is the SQL query to execute
	 * @param row
	 *            is the row type
	 * @return a vector of rows found in DB, or null if no row found
	 */
	protected final synchronized <T extends Type> Collection<T> executeQuery(
			Connection conn, String query, T row) throws IOException {

		MileStone mileStone = new MileStone(xtremweb.database.DBConnPoolThread.class);

		// remove comma from milestone to be able to generate CSV files
		// using benchmarks/milstone/scripts/parse.awk
		final String mq = query.substring(0, Math.min(query.length(), 80)).replace(
				',', '_');
		mileStone.println("<executeQuery>" + mq + "...");

		Connection dbConn = conn;
		if (dbConn == null) {
			try {
				dbConn = popConnection();
			} catch (final Exception e) {
				logger.exception(e);
				logger.fatal(e.toString());
			}
		}
		Vector<T> ret = null;

		ResultSet rs = null;
		Statement stmt = null;

		try {
			stmt = dbConn.createStatement();

			logger.finest(query);

			if (stmt.execute(query)) {
				rs = stmt.getResultSet();
			}
			if (rs == null) {
				rs = stmt.getGeneratedKeys();
			}
			if (rs == null) {
				mileStone.println("<executeQueryError /></executeQuery>");
				notifyAll();
				throw new IOException("can't get SQL results");
			}
		} catch (final Exception ex2) {
			logger.exception("ExecuteQuery  (" + query + ")", ex2);
			try {
				stmt.close();
			} catch (final Exception e) {
				logger.exception(e);
			}
			stmt = null;
			try {
				if (rs != null) {
					rs.close();
				}
			} catch (final Exception e) {
				logger.exception(e);
			}

			if (conn == null) {
				pushConnection(dbConn);
			}

			notifyAll();
			mileStone.println("<executeQueryError /></executeQuery>");
			throw new IOException(ex2.toString());
		}

		try {
			if (row != null) {
				ret = new Vector<T>();
				while (rs.next()) {
					@SuppressWarnings("unchecked")
					final T theRow = (T) row.getClass().newInstance();
					theRow.fill(rs);
					ret.add(theRow);
				}
			}
		} catch (final Exception e) {
			logger.exception(e);
		}
		if ((ret != null) && (ret.size() == 0)) {
			ret = null;
		}

		try {
			if (rs != null) {
				rs.close();
			}
		} catch (final Exception e) {
			logger.exception(e);
		}
		rs = null;

		try {
			if (stmt != null) {
				stmt.close();
			}
		} catch (final Exception e) {
			logger.exception(e);
		}
		stmt = null;

		if (conn == null) {
			pushConnection(dbConn);
		}

		notifyAll();
		mileStone.println("</executeQuery>");
		mileStone = null;
		return ret;
	}

	/**
	 * This executes SQL query
	 * 
	 * @param query
	 *            is the SQL query to execute
	 * @return a vector of rows found in DB, or null if no row found
	 */
	protected synchronized Vector<UID> queryUID(String query)
			throws IOException {

		MileStone mileStone = new MileStone(xtremweb.database.DBConnPoolThread.class);

		if (query.length() < 80) {
			mileStone.println("<executeQuery>" + query);
		} else {
			mileStone
			.println("<executeQuery>" + query.substring(0, 80) + "...");
		}

		Connection dbConn = null;
		try {
			dbConn = popConnection();
		} catch (final Exception e) {
			logger.exception(e);
			logger.fatal(e.toString());
		}
		Vector<UID> ret = null;

		ResultSet rs = null;
		Statement stmt = null;

		try {
			stmt = dbConn.createStatement();

			logger.finest(query);

			if (stmt.execute(query)) {
				rs = stmt.getResultSet();
			}
			if (rs == null) {
				rs = stmt.getGeneratedKeys();
			}
			if (rs == null) {
				mileStone.println("<executeQueryError /><executeQuery>");
				notifyAll();
				throw new IOException("can't get SQL results");
			}
		} catch (final Exception ex2) {
			logger.exception("ExecuteQuery  (" + query + ")", ex2);
			try {
				stmt.close();
			} catch (final Exception e) {
				logger.exception(e);
			}
			stmt = null;
			try {
				rs.close();
			} catch (final Exception e) {
				logger.exception(e);
			}

			pushConnection(dbConn);

			mileStone.println("<executeQueryError /><executeQuery>");
			notifyAll();
			throw new IOException(ex2.toString());
		}

		try {
			ret = new Vector<UID>();
			while (rs.next()) {
				try {
					ret.add(new UID(rs.getString("theuid")));
				} catch (final Exception e) {
					throw new IOException("Can't find UID from result set");
				}
			}
		} catch (final Exception e) {
			logger.exception(e);
		}

		if ((ret != null) && (ret.size() == 0)) {
			ret = null;
		}

		try {
			rs.close();
		} catch (final Exception e) {
			logger.exception(e);
		}
		rs = null;

		try {
			stmt.close();
		} catch (final Exception e) {
			logger.exception(e);
		}
		stmt = null;

		pushConnection(dbConn);

		mileStone.println("</executeQuery>");
		mileStone = null;
		notifyAll();

		return ret;
	}

	/**
	 * This updates the objects contained in the provided vector in the database
	 * table, if needed
	 * 
	 * @param rows
	 *            is the vector of rows to update
	 */
	public synchronized <T extends Table> void update(Collection<T> rows)
			throws IOException {
		update(rows, null);
	}

	/**
	 * This updates objects in the database table
	 * 
	 * @param rows
	 *            is a vector of rows to update
	 * @param _criterias
	 *            is string to use in SQL SELECT WHERE clause if not null
	 *            otherwise TableRow#criterias() is used
	 */
	public synchronized <T extends Table> void update(Collection<T> rows,
			String _criterias) throws IOException {

		try {
			final Iterator<T> rowit = rows.iterator();
			for (; rowit.hasNext();) {
				final T row = rowit.next();
				if (row == null) {
					continue;
				}
				final String criterias = (_criterias != null ? _criterias : row.criteria());
				final String rowset = row.toString();

				if ((criterias == null) || (rowset == null)) {
					throw new IOException("unable to get update criteria");
				}

				final String query = "UPDATE "
						+ config.getProperty(XWPropertyDefs.DBNAME)
						+ "." + row.tableName() + " SET " + rowset + " WHERE "
						+ criterias;

				updateFifo.add(query);
				notify();
			}
		} catch (final Exception e) {
			logger.exception(e);
			throw new IOException(e.toString());
		}
	}

	/**
	 * This calls update(row, null, true)
	 * 
	 * @see #update(Table, String, boolean)
	 */
	public <T extends Table> void update(T row) throws IOException {
		update(row, null, true);
	}

	/**
	 * This calls update(row, criteria, true)
	 * 
	 * @see #update(Table, String, boolean)
	 */
	public synchronized <T extends Table> void update(T row, String criteria)
			throws IOException {
		update(row, criteria, true);
	}

	/**
	 * This updates this object in the database table, if needed
	 * 
	 * @param row
	 *            is the row to update
	 * @param criteria
	 *            is string to use in SQL SELECT WHERE clause if not null
	 *            otherwise TableRow#criteria() is used
	 * @param pool
	 *            uses pool mode if true; execute query immediately if false
	 */
	public synchronized <T extends Table> void update(T row,
			String criteria, boolean pool) throws IOException {

		String rowset = null;
		String query = null;
		try {
			rowset = row.toString();
			if (criteria == null) {
				criteria = row.criteria();
			}

			if ((criteria == null) || (rowset == null)) {
				throw new IOException("unable to get update criteria");
			}

			query = "UPDATE "
					+ config.getProperty(XWPropertyDefs.DBNAME)
					+ "." + row.tableName() + " SET " + rowset + " WHERE "
					+ criteria;

			if (pool == true) {
				logger.finest("updateFifo.add(" + query + ")");
				updateFifo.add(query);
			} else {
				executeQuery(query, row);
			}

			notify();
		} catch (final Exception e) {
			logger.exception(e);
			throw new IOException(e.toString());
		} finally {
			query = null;
			rowset = null;
		}
	}

	/**
	 * This creates a FROM sql statement part for the given row in the form
	 * "dbname.t1 [...][,dbname.t2 [...]]"
	 * 
	 * @see TableRow#fromTableNames()
	 * @since 7.0.0
	 * @param row
	 *            is the TableRow to use in SQL statement
	 * @return a String containing the FROM SQL statement part
	 */
	private String rowTableNames(Type row) throws IOException {
		if (row == null) {
			throw new IOException("row is null ?!?!");
		}
		return config.getProperty(XWPropertyDefs.DBNAME)
				+ "."
				+ row.fromTableNames().replaceAll(
						",",
						","
								+ config
								.getProperty(XWPropertyDefs.DBNAME)
								+ ".");
	}

	/**
	 * This select rows from table
	 * 
	 * @param row
	 *            is the row type
	 * @param criterias
	 *            is string to use in SQL SELECT WHERE clause if not null
	 *            otherwise TableInterface#criteria() is used
	 * @see Table#criteria()
	 * @since 9.2.0
	 */
	public <T extends Type> T selectOne(final T row, final String criterias)
			throws IOException {

		final Vector<T> v = (Vector<T>)select(row, criterias, 1);
		if (v == null) {
			return null;
		}
		return v.firstElement();
	}

	/**
	 * This rows from table
	 * 
	 * @param row
	 *            is the row type
	 * @param criterias
	 *            is string to use in SQL SELECT WHERE clause if not null
	 *            otherwise TableInterface#criteria() is used
	 * @see Table#criteria()
	 * @since 9.2.0
	 */
	public <T extends Type> Collection<T> selectAll(final T row, final String criterias)
			throws IOException {

		return select(row, criterias, config.requestLimit());
	}

	/**
	 * This select rows from table
	 * 
	 * @param row
	 *            is the row type
	 * @param criterias
	 *            is string to use in SQL SELECT WHERE clause if not null
	 *            otherwise TableInterface#criteria() is used
	 * @param limit is the max expected amount of rows
	 * @see Table#criteria()
	 */
	public <T extends Type> Collection<T> select(final T row, final String criterias, final int limit)
			throws IOException {

		try {
			final String groupBy = row.groupBy();
			final String rowcriteria = row.criteria();
			String conditions = null;
			if(rowcriteria != null) {
				conditions = rowcriteria;
				if(criterias != null) {
					conditions += " AND " + criterias;
				}
			} else {
				if(criterias != null) {
					conditions =  criterias;
				}
			}
			final String query = "SELECT "
					+ row.rowSelection()
					+ " FROM "
					+ rowTableNames(row)
					+ (conditions == null ? "" : " WHERE " + conditions)
					+ (groupBy    == null ? "" : " GROUP BY " + groupBy)
					+ " LIMIT " + limit;
//					+ " LIMIT " + config.requestLimit();

			return  executeQuery(query, row);
		} catch (final Exception e) {
			logger.exception(e);
			throw new IOException(e.toString());
		}
	}

	/**
	 * This reads from DB
	 * 
	 * @param row
	 *            is the row type
	 */
	public <T extends Type> Collection<T> select(T row) throws IOException {
		return select(row, null,config.requestLimit());
	}

	/**
	 * This retrieves UID from table
	 * 
	 * @param row
	 *            is the row type
	 * @param criterias
	 *            is string to use in SQL SELECT WHERE clause if not null
	 *            otherwise TableInterface#criteria() is used
	 * @see Table#criteria()
	 */
	public <T extends Table> Vector<UID> selectUID(final T row, final String criterias)
			throws IOException {

		if(row.criteria() == null) {
			throw new IOException("row.criteria == null ?!?");
		}

		try {
			final String rowcriteria = row.criteria();
			String conditions = null;
			if(rowcriteria != null) {
				conditions = rowcriteria;
				if(criterias != null) {
					conditions += " AND " + criterias;
				}
			} else {
				if(criterias != null) {
					conditions =  criterias;
				}
			}
			final String query = "SELECT "
					+ row.rowSelection()
					+ " FROM "
					+ rowTableNames(row)
					+ (conditions == null ? "" : " WHERE " + conditions)
					+ " LIMIT " + config.requestLimit();

			final Vector<UID> ret = queryUID(query);
			return ret;
		} catch (final Exception e) {
			logger.exception(e);
			throw new IOException(e.toString());
		}
	}

	/**
	 * This inserts this object in DB
	 * 
	 * @param row
	 *            is the row to insert
	 */
	public <T extends Type> void insert(T row) throws IOException {

		final String criteria = row.valuesToString();

		if (criteria == null) {
			throw new IOException("unable to get insertion criteria");
		}

		final String query = "INSERT INTO "
				+ config.getProperty(XWPropertyDefs.DBNAME) + "."
				+ row.tableName()
				+ ("(") + row.getColumns() + (") ") + " VALUES (" + criteria
				+ ")";

		executeQuery(query, row);
	}

	/**
	 * Since XWHEP 1.0.0, this does not delete row from table but updates the
	 * row and sets isdeleted flag to true. Hence the row stays in table.
	 * 
	 * @param row
	 *            is the to delete
	 */
	public <T extends Table> void delete(T row) throws IOException {

		try {
			final String criteria = row.criteria();
			if (criteria == null) {
				throw new IOException("unable to get delete criteria");
			}

			String query = "INSERT INTO "
					+ config.getProperty(XWPropertyDefs.DBNAME)
					+ "."
					+ row.tableName() + HISTORYSUFFIX
					+ " SELECT * FROM "
					+ config.getProperty(XWPropertyDefs.DBNAME)
					+ "." + row.tableName()
					+ " WHERE " + criteria;
			executeQuery(query, row);

			query = "DELETE FROM "
					+ config.getProperty(XWPropertyDefs.DBNAME)
					+ "." + row.tableName() +
					" WHERE " + criteria;
			executeQuery(query, row);
		} catch (final Exception e) {
			logger.exception(e);
			throw new IOException(e.toString());
		}
	}

	/**
	 * This set all server works to WAITING status
	 * 
	 * @param serverName
	 *            is the server name
	 */
	public void unlockWorks(String serverName) {
		try {
			String query = "UPDATE "
					+ config.getProperty(XWPropertyDefs.DBNAME)
					+ ".works SET " + WorkInterface.Columns.STATUS.toString()
					+ "='" + StatusEnum.WAITING + "',"
					+ WorkInterface.Columns.SERVER.toString()
					+ "='NULL'  WHERE "
					+ WorkInterface.Columns.SERVER.toString() + "='"
					+ serverName + "' and(("
					+ WorkInterface.Columns.STATUS.toString() + "='"
					+ StatusEnum.WAITING + "' or "
					+ WorkInterface.Columns.STATUS.toString() + "='"
					+ StatusEnum.PENDING + "')";

			query += " OR ISNULL(status))";

			executeQuery(query, null);
		} catch (final Exception e) {
			logger.exception(e);
		}
	}

	/**
	 * @return the instance
	 */
	public static DBConnPoolThread getInstance() {
		return instance;
	}

	/**
	 * @param instance the instance to set
	 */
	public static void setInstance(DBConnPoolThread instance) {
		DBConnPoolThread.instance = instance;
	}
}
