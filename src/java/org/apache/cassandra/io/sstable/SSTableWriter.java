/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 * 
 *   http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.apache.cassandra.io.sstable;

import java.io.DataOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOError;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.HashSet;
import java.util.Set;

import org.apache.cassandra.config.CFMetaData;
import org.apache.cassandra.config.DatabaseDescriptor;
import org.apache.cassandra.db.ColumnFamily;
import org.apache.cassandra.db.ColumnFamilyStore;
import org.apache.cassandra.db.DecoratedKey;
import org.apache.cassandra.db.Table;
import org.apache.cassandra.dht.IPartitioner;
import org.apache.cassandra.io.AbstractCompactedRow;
import org.apache.cassandra.io.ICompactionInfo;
import org.apache.cassandra.io.util.BufferedRandomAccessFile;
import org.apache.cassandra.io.util.FileMark;
import org.apache.cassandra.io.util.FileUtils;
import org.apache.cassandra.io.util.SegmentedFile;
import org.apache.cassandra.service.StorageService;
import org.apache.cassandra.utils.BloomFilter;
import org.apache.cassandra.utils.EstimatedHistogram;
import org.apache.cassandra.utils.FBUtilities;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class SSTableWriter extends SSTable
{
    private static Logger logger = LoggerFactory.getLogger(SSTableWriter.class);

    private IndexWriter iwriter;
    private SegmentedFile.Builder dbuilder;
    private final BufferedRandomAccessFile dataFile;
    private DecoratedKey lastWrittenKey;
    private FileMark dataMark;

    public SSTableWriter(String filename, long keyCount) throws IOException
    {
        this(filename, keyCount, DatabaseDescriptor.getCFMetaData(Descriptor.fromFilename(filename)), StorageService.getPartitioner());
    }

    public SSTableWriter(String filename, long keyCount, CFMetaData metadata, IPartitioner partitioner) throws IOException
    {
        super(Descriptor.fromFilename(filename), new HashSet<Component>(), metadata, partitioner, SSTable.defaultRowHistogram(), SSTable.defaultColumnHistogram());
        iwriter = new IndexWriter(descriptor, partitioner, keyCount);
        dbuilder = SegmentedFile.getBuilder(DatabaseDescriptor.getDiskAccessMode());
        dataFile = new BufferedRandomAccessFile(getFilename(), "rw", DatabaseDescriptor.getInMemoryCompactionLimit());

        // the set of required components
        components.add(Component.DATA);
        components.add(Component.FILTER);
        components.add(Component.PRIMARY_INDEX);
        components.add(Component.STATS);
    }
    
    /** something bad happened and the files associated with this writer need to be deleted. */
    public void abort()
    {
        try
        {
            dataFile.close();
            FileUtils.deleteWithConfirm(dataFile.getPath());
        }
        catch (IOException ex) 
        {
            logger.error(String.format("Caught exception while deleting aborted sstable (%s). %s", dataFile.getPath(), ex.getMessage()));
        }
        
        try
        {
            iwriter.close();
            FileUtils.deleteWithConfirm(descriptor.filenameFor(SSTable.COMPONENT_INDEX));
        }
        catch (IOException ex)
        {
            logger.error(String.format("Caught exception while deleting aborted sstable (%s). %s", descriptor.filenameFor(SSTable.COMPONENT_INDEX), ex.getMessage()));
        }
    }

    public void mark()
    {
        dataMark = dataFile.mark();
        iwriter.mark();
    }

    public void reset()
    {
        try
        {
            dataFile.reset(dataMark);
            iwriter.reset();
        }
        catch (IOException e)
        {
            throw new IOError(e);
        }
    }

    private long beforeAppend(DecoratedKey decoratedKey) throws IOException
    {
        if (decoratedKey == null)
        {
            throw new IOException("Keys must not be null.");
        }
        if (lastWrittenKey != null && lastWrittenKey.compareTo(decoratedKey) > 0)
        {
            logger.info("Last written key : " + lastWrittenKey);
            logger.info("Current key : " + decoratedKey);
            logger.info("Writing into file " + getFilename());
            throw new IOException("Keys must be written in ascending order.");
        }
        return (lastWrittenKey == null) ? 0 : dataFile.getFilePointer();
    }

    private void afterAppend(DecoratedKey decoratedKey, long dataPosition) throws IOException
    {
        lastWrittenKey = decoratedKey;

        if (logger.isTraceEnabled())
            logger.trace("wrote " + decoratedKey + " at " + dataPosition);
        iwriter.afterAppend(decoratedKey, dataPosition);
        dbuilder.addPotentialBoundary(dataPosition);
    }

    public void append(AbstractCompactedRow row) throws IOException
    {
        long currentPosition = beforeAppend(row.key);
        FBUtilities.writeShortByteArray(row.key.key, dataFile);
        row.write(dataFile);
        estimatedRowSize.add(dataFile.getFilePointer() - currentPosition);
        estimatedColumnCount.add(row.columnCount());
        afterAppend(row.key, currentPosition);
    }

    public void append(DecoratedKey decoratedKey, ColumnFamily cf) throws IOException
    {
        long startPosition = beforeAppend(decoratedKey);
        FBUtilities.writeShortByteArray(decoratedKey.key, dataFile);
        // write placeholder for the row size, since we don't know it yet
        long sizePosition = dataFile.getFilePointer();
        dataFile.writeLong(-1);
        // write out row data
        int columnCount = ColumnFamily.serializer().serializeWithIndexes(cf, dataFile);
        // seek back and write the row size (not including the size Long itself)
        long endPosition = dataFile.getFilePointer();
        dataFile.seek(sizePosition);
        dataFile.writeLong(endPosition - (sizePosition + 8));
        // finally, reset for next row
        dataFile.seek(endPosition);
        afterAppend(decoratedKey, startPosition);
        estimatedRowSize.add(endPosition - startPosition);
        estimatedColumnCount.add(columnCount);
    }

    public void append(DecoratedKey decoratedKey, ByteBuffer value) throws IOException
    {
        long currentPosition = beforeAppend(decoratedKey);
        FBUtilities.writeShortByteArray(decoratedKey.key, dataFile);
        assert value.remaining() > 0;
        dataFile.writeLong(value.remaining());
        dataFile.write(value.array(),value.position()+value.arrayOffset(),value.remaining());
        afterAppend(decoratedKey, currentPosition);
    }

    public SSTableReader closeAndOpenReader() throws IOException
    {
        return closeAndOpenReader(System.currentTimeMillis());
    }

    public SSTableReader closeAndOpenReader(long maxDataAge) throws IOException
    {
        // index and filter
        iwriter.close();

        // main data
        long position = dataFile.getFilePointer();
        dataFile.close(); // calls force
        FileUtils.truncate(dataFile.getPath(), position);

        // write sstable statistics
        writeStatistics(descriptor, estimatedRowSize, estimatedColumnCount);

        // remove the 'tmp' marker from all components
        final Descriptor newdesc = rename(descriptor, components);


        // finalize in-memory state for the reader
        SegmentedFile ifile = iwriter.builder.complete(newdesc.filenameFor(SSTable.COMPONENT_INDEX));
        SegmentedFile dfile = dbuilder.complete(newdesc.filenameFor(SSTable.COMPONENT_DATA));
        SSTableReader sstable = SSTableReader.internalOpen(newdesc, components, metadata, partitioner, ifile, dfile, iwriter.summary, iwriter.bf, maxDataAge, estimatedRowSize, estimatedColumnCount);
        iwriter = null;
        dbuilder = null;
        return sstable;
    }

    private static void writeStatistics(Descriptor desc, EstimatedHistogram rowSizes, EstimatedHistogram columnnCounts) throws IOException
    {
        DataOutputStream out = new DataOutputStream(new FileOutputStream(desc.filenameFor(SSTable.COMPONENT_STATS)));
        EstimatedHistogram.serializer.serialize(rowSizes, out);
        EstimatedHistogram.serializer.serialize(rowSizes, out);
        out.close();
    }

    static Descriptor rename(Descriptor tmpdesc, Set<Component> components)
    {
        Descriptor newdesc = tmpdesc.asTemporary(false);
        try
        {
            for (Component component : components)
                FBUtilities.renameWithConfirm(tmpdesc.filenameFor(component), newdesc.filenameFor(component));
        }
        catch (IOException e)
        {
            throw new IOError(e);
        }
        return newdesc;
    }

    public long getFilePointer()
    {
        return dataFile.getFilePointer();
    }
    
    public static Builder createBuilder(Descriptor desc)
    {
        if (!desc.isLatestVersion)
            // TODO: streaming between different versions will fail: need support for
            // recovering other versions to provide a stable streaming api
            throw new RuntimeException(String.format("Cannot recover SSTable with version %s (current version %s).",
                                                     desc.version, Descriptor.CURRENT_VERSION));

        return new Builder(desc);
    }

    /**
     * Removes the given SSTable from temporary status and opens it, rebuilding the
     * bloom filter and row index from the data file.
     */
    public static class Builder implements ICompactionInfo
    {
        private final Descriptor desc;
        public final ColumnFamilyStore cfs;
        private final RowIndexer indexer;

        public Builder(Descriptor desc)
        {
            this.desc = desc;
            cfs = Table.open(desc.ksname).getColumnFamilyStore(desc.cfname);
            try
            {
                indexer = cfs.metadata.cfType.isCounter() ? new CounterRowIndexer(desc, cfs) : new RowIndexer(desc, cfs);
            }
            catch (IOException e)
            {
                throw new IOError(e);
            }
        }

        public SSTableReader build() throws IOException
        {
            File ifile = new File(desc.filenameFor(SSTable.COMPONENT_INDEX));
            File ffile = new File(desc.filenameFor(SSTable.COMPONENT_FILTER));
            assert !ifile.exists();
            assert !ffile.exists();

            long estimatedRows = indexer.prepareIndexing();

            // build the index and filter
            long rows = indexer.index();

            logger.debug("estimated row count was {} of real count", ((double)estimatedRows) / rows);
            return SSTableReader.open(rename(desc, SSTable.componentsFor(desc)));
        }

        public long getTotalBytes()
        {
            try
            {
                return indexer.dfile.length();
            }
            catch (IOException e)
            {
                throw new IOError(e);
            }
        }

        public long getBytesRead()
        {
            return indexer.dfile.getFilePointer();
        }

        public String getTaskType()
        {
            return "SSTable rebuild";
        }
    }

    static class RowIndexer
    {
        protected final Descriptor desc;
        protected final BufferedRandomAccessFile dfile;
        protected final ColumnFamilyStore cfs;

        protected IndexWriter iwriter;

        RowIndexer(Descriptor desc, ColumnFamilyStore cfs) throws IOException
        {
            this(desc, cfs, new BufferedRandomAccessFile(desc.filenameFor(SSTable.COMPONENT_DATA), "r", 8 * 1024 * 1024));
        }

        protected RowIndexer(Descriptor desc, ColumnFamilyStore cfs, BufferedRandomAccessFile dfile) throws IOException
        {
            this.desc = desc;
            this.cfs = cfs;
            this.dfile = dfile;
        }

        long prepareIndexing() throws IOException
        {
            long estimatedRows;
            try
            {
                estimatedRows = SSTable.estimateRowsFromData(desc, dfile);
                iwriter = new IndexWriter(desc, StorageService.getPartitioner(), estimatedRows);
                return estimatedRows;
            }
            catch(IOException e)
            {
                dfile.close();
                throw e;
            }
        }

        long index() throws IOException
        {
            try
            {
                EstimatedHistogram rowSizes = SSTable.defaultRowHistogram();
                EstimatedHistogram columnCounts = SSTable.defaultColumnHistogram();
                long rows = 0;
                DecoratedKey key;
                long rowPosition = 0;
                while (rowPosition < dfile.length())
                {
                    key = SSTableReader.decodeKey(StorageService.getPartitioner(), desc, FBUtilities.readShortByteArray(dfile));
                    iwriter.afterAppend(key, rowPosition);

                    long dataSize = SSTableReader.readRowSize(dfile, desc);
                    rowPosition = dfile.getFilePointer() + dataSize; // next row

                    IndexHelper.skipBloomFilter(dfile);
                    IndexHelper.skipIndex(dfile);

                    long mark = dfile.getFilePointer();

                    ColumnFamily cf = ColumnFamily.create(cfs.metadata);
                    ColumnFamily.serializer().deserializeFromSSTableNoColumns(cf, dfile);

                    int columnCount = processColumnFamily(mark, cf);

                    rowSizes.add(dataSize);
                    columnCounts.add(columnCount);

                    dfile.seek(rowPosition);
                    rows++;
                }

                writeStatistics(desc, rowSizes, columnCounts);
                return rows;
            }
            finally
            {
                try
                {
                    dfile.close();
                    iwriter.close();
                }
                catch (IOException e)
                {
                    throw new IOError(e);
                }
            }
        }

        protected int processColumnFamily(long mark, ColumnFamily cf) throws IOException
        {
            return dfile.readInt();
        }
    }

    static class CounterRowIndexer extends RowIndexer
    {
        CounterRowIndexer(Descriptor desc, ColumnFamilyStore cfs) throws IOException
        {
            super(desc, cfs, new BufferedRandomAccessFile(desc.filenameFor(SSTable.COMPONENT_DATA), "rw", 8 * 1024 * 1024));
        }

        @Override
        protected int processColumnFamily(long mark, ColumnFamily cf) throws IOException
        {
            ColumnFamily.serializer().deserializeColumnsNoExpiration(dfile, cf);

            dfile.seek(mark);
            ColumnFamily.serializer().serializeForSSTable(cf, dfile);
            return cf.getColumnCount();
        }
    }

    /**
     * Encapsulates writing the index and filter for an SSTable. The state of this object is not valid until it has been closed.
     */
    static class IndexWriter
    {
        private final BufferedRandomAccessFile indexFile;
        public final Descriptor desc;
        public final IPartitioner partitioner;
        public final SegmentedFile.Builder builder;
        public final IndexSummary summary;
        public final BloomFilter bf;
        private FileMark mark;

        IndexWriter(Descriptor desc, IPartitioner part, long keyCount) throws IOException
        {
            this.desc = desc;
            this.partitioner = part;
            indexFile = new BufferedRandomAccessFile(desc.filenameFor(SSTable.COMPONENT_INDEX), "rw", 8 * 1024 * 1024);
            builder = SegmentedFile.getBuilder(DatabaseDescriptor.getIndexAccessMode());
            summary = new IndexSummary(keyCount);
            bf = BloomFilter.getFilter(keyCount, 15);
        }

        public void afterAppend(DecoratedKey key, long dataPosition) throws IOException
        {
            bf.add(key.key);
            long indexPosition = indexFile.getFilePointer();
            FBUtilities.writeShortByteArray(key.key, indexFile);
            indexFile.writeLong(dataPosition);
            if (logger.isTraceEnabled())
                logger.trace("wrote index of " + key + " at " + indexPosition);

            summary.maybeAddEntry(key, indexPosition);
            builder.addPotentialBoundary(indexPosition);
        }

        /**
         * Closes the index and bloomfilter, making the public state of this writer valid for consumption.
         */
        public void close() throws IOException
        {
            // bloom filter
            FileOutputStream fos = new FileOutputStream(desc.filenameFor(SSTable.COMPONENT_FILTER));
            DataOutputStream stream = new DataOutputStream(fos);
            BloomFilter.serializer().serialize(bf, stream);
            stream.flush();
            fos.getFD().sync();
            stream.close();

            // index
            long position = indexFile.getFilePointer();
            indexFile.close(); // calls force
            FileUtils.truncate(indexFile.getPath(), position);

            // finalize in-memory index state
            summary.complete();
        }

        public void mark()
        {
            mark = indexFile.mark();
        }

        public void reset() throws IOException
        {
            // we can't un-set the bloom filter addition, but extra keys in there are harmless.
            // we can't reset dbuilder either, but that is the last thing called in afterappend so
            // we assume that if that worked then we won't be trying to reset.
            indexFile.reset(mark);
        }
    }
}
