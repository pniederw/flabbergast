package flabbergast;

import flabbergast.Lookup.DoLookup;
import flabbergast.time.BaseTime;
import java.sql.Types;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.Connection;
import java.sql.Statement;
import java.sql.SQLException;
import java.sql.Types;
import java.util.HashMap;
import java.util.Map;
import java.util.List;
import java.util.ArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import org.joda.time.DateTime;

public class JdbcQuery extends Computation {
    private static abstract class NameChooser {
        public final static NameChooser DEFAULT = new NameChooser() {
            @Override
            public String invoke(ResultSet rs, long it) throws SQLException {
                return TaskMaster.ordinalNameStr(it);
            }
        };
        public abstract String invoke(ResultSet rs, long it)
        throws SQLException;
    }

    private static abstract class Retriever {
        abstract void invoke(ResultSet rs, MutableFrame frame,
                             TaskMaster task_master) throws SQLException;
    }
    private static class UnpackRetriever extends Retriever {
        private final String name;
        private final int position;
        private final Unpacker unpacker;
        UnpackRetriever(String name, int position, Unpacker unpacker) {
            this.name = name;
            this.position = position;
            this.unpacker = unpacker;
        }
        @Override
        void invoke(ResultSet rs, MutableFrame frame, TaskMaster task_master)
        throws SQLException {
            Object result = unpacker.invoke(rs, position, task_master);
            frame.set(name, rs.wasNull() ? Unit.NULL : result);
        }
    }
    static abstract class Unpacker {
        abstract Object invoke(ResultSet rs, int position,
                               TaskMaster task_master) throws SQLException;
    }

    static final Map<Integer, Unpacker> unpackers = new HashMap<Integer, Unpacker>();
    static {
        addUnpacker(new Unpacker() {
            @Override
            Object invoke(ResultSet rs, int position, TaskMaster task_master)
            throws SQLException {
                String str = rs.getString(position);
                return str == null ? null : new SimpleStringish(str);
            }
        }, Types.CHAR, Types.VARCHAR, Types.LONGVARCHAR);
        addUnpacker(new Unpacker() {
            @Override
            Object invoke(ResultSet rs, int position, TaskMaster task_master)
            throws SQLException {
                return rs.getLong(position);
            }
        }, Types.TINYINT, Types.SMALLINT, Types.INTEGER, Types.BIGINT);
        addUnpacker(new Unpacker() {
            @Override
            Object invoke(ResultSet rs, int position, TaskMaster task_master)
            throws SQLException {
                return rs.getDouble(position);
            }
        }, Types.REAL, Types.FLOAT, Types.DOUBLE);
        addUnpacker(new Unpacker() {
            @Override
            Object invoke(ResultSet rs, int position, TaskMaster task_master)
            throws SQLException {
                return rs.getBoolean(position);
            }
        }, Types.BIT);
        addUnpacker(new Unpacker() {
            @Override
            Object invoke(ResultSet rs, int position, TaskMaster task_master)
            throws SQLException {
                return BaseTime.makeTime(
                           new DateTime(rs.getTimestamp(position)), task_master);
            }
        }, Types.DATE, Types.TIME, Types.TIMESTAMP);
        addUnpacker(new Unpacker() {
            @Override
            Object invoke(ResultSet rs, int position, TaskMaster task_master)
            throws SQLException {
                return rs.getBytes(position);
            }
        }, Types.BLOB, Types.BINARY, Types.NCHAR, Types.NCLOB, Types.VARBINARY);
    }

    static void addUnpacker(Unpacker unpacker, int... sql_types) {
        for (int sql_type : sql_types) {
            unpackers.put(sql_type, unpacker);
        }
    }

    private final SourceReference source_ref;
    private final Context context;
    private final Frame self;
    private final AtomicInteger interlock = new AtomicInteger(4);
    private Connection connection = null;
    private String query = null;
    private Template row_tmpl = null;

    public JdbcQuery(TaskMaster task_master, SourceReference source_ref,
                     Context context, Frame self, Frame container) {
        super(task_master);
        this.source_ref = source_ref;
        this.context = context;
        this.self = self;
    }

    @Override
    public void run() {
        if (connection == null) {
            new Lookup(task_master, source_ref, new String[] {"connection"},
            context).listen(new ConsumeResult() {
                @Override
                public void consume(Object return_value) {
                    if (return_value instanceof ReflectedFrame) {
                        Object backing = ((ReflectedFrame) return_value)
                                         .getBacking();
                        if (backing instanceof Connection) {
                            connection = (Connection) backing;
                            if (interlock.decrementAndGet() == 0) {
                                task_master.slot(JdbcQuery.this);
                            }
                            return;
                        }
                    }
                    task_master
                    .reportOtherError(source_ref,
                                      "Expected “connection” to come from “sql:” import.");
                }
            });
            new Lookup(task_master, source_ref, new String[] {"sql_query"},
            context).listen(new ConsumeResult() {
                @Override
                public void consume(Object return_value) {
                    if (return_value instanceof Stringish) {
                        query = return_value.toString();
                        if (interlock.decrementAndGet() == 0) {
                            task_master.slot(JdbcQuery.this);
                        }
                        return;
                    }
                    task_master.reportOtherError(source_ref, String.format(
                                                     "Expected type Str for “sql_query”, but got %s.",
                                                     Stringish.nameForClass(return_value.getClass())));
                }
            });
            new Lookup(task_master, source_ref, new String[] {"sql_row_tmpl"},
            context).listen(new ConsumeResult() {
                @Override
                public void consume(Object return_value) {
                    if (return_value instanceof Template) {
                        row_tmpl = (Template) return_value;
                        if (interlock.decrementAndGet() == 0) {
                            task_master.slot(JdbcQuery.this);
                        }
                        return;
                    }
                    task_master.reportOtherError(
                        source_ref,
                        String.format(
                            "Expected type Str for “sql_row_tmpl”, but got %s.",
                            Stringish.nameForClass(return_value
                                                   .getClass())));
                }
            });
            if (interlock.decrementAndGet() > 0) {
                return;
            }
        }
        try {
            Statement stmt = connection.createStatement();
            ResultSet rs = stmt.executeQuery(query);

            ResultSetMetaData rsmd = rs.getMetaData();
            NameChooser name_chooser = NameChooser.DEFAULT;
            List<Retriever> retrievers = new ArrayList<Retriever>();
            for (int col = 1; col <= rsmd.getColumnCount(); col++) {
                if (rsmd.getColumnLabel(col).equals("ATTRNAME")) {
                    final int index = col;
                    name_chooser = new NameChooser() {
                        @Override
                        public String invoke(ResultSet rs, long it)
                        throws SQLException {
                            return rs.getString(index);
                        }
                    };
                    continue;
                }
                if (rsmd.getColumnLabel(col).startsWith("$")) {
                    final String attr_name = rsmd.getColumnLabel(col)
                                             .substring(1);
                    final int column = col;
                    if (!task_master.verifySymbol(source_ref, attr_name)) {
                        return;
                    }
                    retrievers.add(new Retriever() {
                        @Override
                        void invoke(ResultSet rs, MutableFrame frame,
                                    TaskMaster task_master) throws SQLException {
                            String result = rs.getString(column);
                            frame.set(attr_name, rs.wasNull()
                                      ? Unit.NULL
                                      : new DoLookup(result.split("\\.")));
                        }
                    });
                    continue;
                }
                if (rsmd.getColumnType(col) == Types.NULL) {
                    // This is here because SQLite like to pass weird data.
                    continue;
                }
                Unpacker unpacker = unpackers.get(rsmd.getColumnType(col));
                if (unpacker == null) {
                    task_master
                    .reportOtherError(
                        source_ref,
                        String.format(
                            "Cannot convert SQL type “%s” for column “%s” into Flabbergast type.",
                            rsmd.getColumnTypeName(col),
                            rsmd.getColumnLabel(col)));
                    return;
                }
                if (!task_master.verifySymbol(source_ref,
                                              rsmd.getColumnLabel(col))) {
                    return;
                }
                String name = rsmd.getColumnLabel(col);
                retrievers.add(new UnpackRetriever(name, col, unpacker));
            }

            MutableFrame list = new MutableFrame(task_master, source_ref,
                                                 context, self);
            for (int it = 1; rs.next(); it++) {
                MutableFrame frame = new MutableFrame(task_master,
                                                      new JunctionReference(String.format(
                                                              "SQL template instantiation row %d", it),
                                                              "<sql>", 0, 0, 0, 0, source_ref,
                                                              row_tmpl.getSourceReference()), Context.append(
                                                                      list.getContext(), row_tmpl.getContext()), list);
                for (Retriever r : retrievers) {
                    r.invoke(rs, frame, task_master);
                }
                for (String name : row_tmpl) {
                    if (!frame.has(name)) {
                        frame.set(name, row_tmpl.get(name));
                    }
                }
                list.set(name_chooser.invoke(rs, it), frame);
            }
            list.slot();
            result = list;
        } catch (SQLException e) {
            task_master.reportOtherError(source_ref, e.getMessage());
        }
    }
}
