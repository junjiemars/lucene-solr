package org.apache.lucene.index;

/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

import org.apache.lucene.analysis.MockAnalyzer;
import org.apache.lucene.analysis.TokenStream;
import org.apache.lucene.analysis.tokenattributes.CharTermAttribute;
import org.apache.lucene.codecs.compressing.CompressingCodec;
import org.apache.lucene.codecs.compressing.CompressingStoredFieldsFormat;
import org.apache.lucene.codecs.compressing.CompressingStoredFieldsIndex;
import org.apache.lucene.codecs.compressing.CompressionMode;
import org.apache.lucene.document.Document;
import org.apache.lucene.document.Field;
import org.apache.lucene.document.FieldType;
import org.apache.lucene.document.TextField;
import org.apache.lucene.index.FieldInfo.IndexOptions;
import org.apache.lucene.store.BaseDirectoryWrapper;
import org.apache.lucene.store.MockDirectoryWrapper;
import org.apache.lucene.util.LuceneTestCase.SuppressCodecs;
import org.apache.lucene.util.LuceneTestCase;
import org.apache.lucene.util.TimeUnits;
import org.apache.lucene.util._TestUtil;
import com.carrotsearch.randomizedtesting.annotations.TimeoutSuite;
import com.carrotsearch.randomizedtesting.generators.RandomInts;
import com.carrotsearch.randomizedtesting.generators.RandomPicks;

/**
 * Test indexes ~82M docs with 26 terms each, so you get > Integer.MAX_VALUE terms/docs pairs
 * @lucene.experimental
 */
@SuppressCodecs({ "SimpleText", "Memory", "Direct" })
@TimeoutSuite(millis = 4 * TimeUnits.HOUR)
public class Test2BPostings extends LuceneTestCase {

  @Nightly
  public void test() throws Exception {
    BaseDirectoryWrapper dir = newFSDirectory(_TestUtil.getTempDir("2BPostings"));
    if (dir instanceof MockDirectoryWrapper) {
      ((MockDirectoryWrapper)dir).setThrottling(MockDirectoryWrapper.Throttling.NEVER);
    }

    IndexWriterConfig iwc = new IndexWriterConfig(TEST_VERSION_CURRENT, new MockAnalyzer(random()))
        .setMaxBufferedDocs(IndexWriterConfig.DISABLE_AUTO_FLUSH)
        .setRAMBufferSizeMB(256.0)
        .setMergeScheduler(new ConcurrentMergeScheduler())
        .setMergePolicy(newLogMergePolicy(false, 10))
        .setOpenMode(IndexWriterConfig.OpenMode.CREATE);

    if (iwc.getCodec() instanceof CompressingCodec) {
      CompressingStoredFieldsFormat fmt = (CompressingStoredFieldsFormat) ((CompressingCodec) iwc.getCodec()).storedFieldsFormat();
      // NOTE: copied from CompressingCodec.randomInstance(), but fixed to not
      // use any memory index ... maybe we can instead add
      // something like CompressingMemory to the
      // SuppressCodecs list...?:
      final CompressionMode mode = RandomPicks.randomFrom(random(), CompressionMode.values());
      final int chunkSize = RandomInts.randomIntBetween(random(), 1, 500);
      iwc.setCodec(new CompressingCodec(mode, chunkSize, CompressingStoredFieldsIndex.DISK_DOC));
    }
    
    IndexWriter w = new IndexWriter(dir, iwc);

    MergePolicy mp = w.getConfig().getMergePolicy();
    if (mp instanceof LogByteSizeMergePolicy) {
     // 1 petabyte:
     ((LogByteSizeMergePolicy) mp).setMaxMergeMB(1024*1024*1024);
    }

    Document doc = new Document();
    FieldType ft = new FieldType(TextField.TYPE_NOT_STORED);
    ft.setOmitNorms(true);
    ft.setIndexOptions(IndexOptions.DOCS_ONLY);
    Field field = new Field("field", new MyTokenStream(), ft);
    doc.add(field);
    
    final int numDocs = (Integer.MAX_VALUE / 26) + 1;
    for (int i = 0; i < numDocs; i++) {
      w.addDocument(doc);
      if (VERBOSE && i % 100000 == 0) {
        System.out.println(i + " of " + numDocs + "...");
      }
    }
    w.forceMerge(1);
    w.close();
    dir.close();
  }
  
  public static final class MyTokenStream extends TokenStream {
    private final CharTermAttribute termAtt = addAttribute(CharTermAttribute.class);
    private final char buffer[];
    int index;

    public MyTokenStream() {
      termAtt.setLength(1);
      buffer = termAtt.buffer();
    }
    
    @Override
    public boolean incrementToken() {
      if (index <= 'z') {
        buffer[0] = (char) index++;
        return true;
      }
      return false;
    }
    
    @Override
    public void reset() {
      index = 'a';
    }
  }
}
