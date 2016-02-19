/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.hadoop.hive.ql.udf.generic;

import java.util.*;

import org.apache.hadoop.hive.serde2.io.DoubleWritable;
import org.apache.hadoop.hive.serde2.objectinspector.primitive.DoubleObjectInspector;


/**
 * A generic, re-usable histogram class that supports partial aggregations.
 * The algorithm is a heuristic adapted from the following paper:
 * Yael Ben-Haim and Elad Tom-Tov, "A streaming parallel decision tree algorithm",
 * J. Machine Learning Research 11 (2010), pp. 849--872. Although there are no approximation
 * guarantees, it appears to work well with adequate data and a large (e.g., 20-80) number
 * of histogram bins.
 */
public class FastNumericHistogram {
  /**
   * The Coord class defines a histogram bin, which is just an (x,y) pair.
   */
  static class Coord implements Comparable {
    double x;
    double y;

    public int compareTo(Object other) {
      return Double.compare(x, ((Coord) other).x);
    }
  };

  // Class variables
  private int nbins;
  private int maxBins;
  private int nusedbins;
  private ArrayList<Coord> bins;
  private Random prng;

  /**
   * Creates a new histogram object. Note that the allocate() or merge()
   * method must be called before the histogram can be used.
   */
  public FastNumericHistogram() {
    nbins = 0;
    nusedbins = 0;
    maxBins = 0;
    bins = null;

    // init the RNG for breaking ties in histogram merging. A fixed seed is specified here
    // to aid testing, but can be eliminated to use a time-based seed (which would
    // make the algorithm non-deterministic).
    prng = new Random(31183);
  }

  /**
   * Resets a histogram object to its initial state. allocate() or merge() must be
   * called again before use.
   */
  public void reset() {
    bins = null;
    nbins = maxBins = nusedbins = 0;
  }

  /**
   * Returns the number of bins currently being used by the histogram.
   */
  public int getUsedBins() {
    return nusedbins;
  }

  /**
   * Returns true if this histogram object has been initialized by calling merge()
   * or allocate().
   */
  public boolean isReady() {
    return nbins != 0;
  }

  /**
   * Returns a particular histogram bin.
   */
  public Coord getBin(int b) {
    return bins.get(b);
  }

  /**
   * Sets the number of histogram bins to use for approximating data.
   *
   * @param num_bins Number of non-uniform-width histogram bins to use
   */
  public void allocate(int num_bins) {
    nbins = num_bins;
    maxBins = nbins * 2;
    bins = new ArrayList<>();
    nusedbins = 0;
  }

  /**
   * Takes a serialized histogram created by the serialize() method and merges
   * it with the current histogram object.
   *
   * @param other A serialized histogram created by the serialize() method
   * @see #merge
   */
  public void merge(List other, DoubleObjectInspector doi) {
    if(other == null) {
      return;
    }

    if(nbins == 0 || nusedbins == 0)  {
      // Our aggregation buffer has nothing in it, so just copy over 'other'
      // by deserializing the ArrayList of (x,y) pairs into an array of Coord objects
      nbins = (int) doi.get(other.get(0));
      nusedbins = (other.size()-1)/2;
      bins = new ArrayList<Coord>(nusedbins);
      for (int i = 1; i < other.size(); i+=2) {
        Coord bin = new Coord();
        bin.x = doi.get(other.get(i));
        bin.y = doi.get(other.get(i+1));
        bins.add(bin);
      }
    } else {
      // The aggregation buffer already contains a partial histogram. Therefore, we need
      // to merge histograms using Algorithm #2 from the Ben-Haim and Tom-Tov paper.

      ArrayList<Coord> tmp_bins = new ArrayList<Coord>(nusedbins + (other.size()-1)/2);
      // Copy all the histogram bins from us and 'other' into an overstuffed histogram
      for (int i = 0; i < nusedbins; i++) {
        Coord bin = new Coord();
        bin.x = bins.get(i).x;
        bin.y = bins.get(i).y;
        tmp_bins.add(bin);
      }
      for (int j = 1; j < other.size(); j += 2) {
        Coord bin = new Coord();
        bin.x = doi.get(other.get(j));
        bin.y = doi.get(other.get(j+1));
        tmp_bins.add(bin);
      }
      Collections.sort(tmp_bins);

      // Now trim the overstuffed histogram down to the correct number of bins
      bins = tmp_bins;
      nusedbins += (other.size()-1)/2;
      trim();
    }
  }


  /**
   * Adds a new data point to the histogram approximation. Make sure you have
   * called either allocate() or merge() first. This method implements Algorithm #1
   * from Ben-Haim and Tom-Tov, "A Streaming Parallel Decision Tree Algorithm", JMLR 2010.
   *
   * @param v The data point to add to the histogram approximation.
   */
  public void add(double v) {
    // Binary search to find the closest bucket that v should go into.
    // 'bin' should be interpreted as the bin to shift right in order to accomodate
    // v. As a result, bin is in the range [0,N], where N means that the value v is
    // greater than all the N bins currently in the histogram. It is also possible that
    // a bucket centered at 'v' already exists, so this must be checked in the next step.
    int bin = 0;
    for(int l=0, r=nusedbins; l < r; ) {
      bin = (l+r)/2;
      if(bins.get(bin).x > v) {
        r = bin;
      } else {
        if(bins.get(bin).x < v) {
          l = ++bin;
        } else {
          break; // break loop on equal comparator
        }
      }
    }

    // If we found an exact bin match for value v, then just increment that bin's count.
    // Otherwise, we need to insert a new bin and trim the resulting histogram back to size.
    // A possible optimization here might be to set some threshold under which 'v' is just
    // assumed to be equal to the closest bin -- if fabs(v-bins[bin].x) < THRESHOLD, then
    // just increment 'bin'. This is not done now because we don't want to make any
    // assumptions about the range of numeric data being analyzed.
    if(bin < nusedbins && bins.get(bin).x == v) {
      bins.get(bin).y++;
    } else {
      Coord newBin = new Coord();
      newBin.x = v;
      newBin.y = 1;
      bins.add(bin, newBin);

      // Trim the bins down to the correct number of bins.
      if(++nusedbins > maxBins) {
        trim();
      }
    }
  }

  /**
   * Trims a histogram down to 'nbins' bins by iteratively merging the closest bins.
   * If two pairs of bins are equally close to each other, decide uniformly at random which
   * pair to merge, based on a PRNG.
   * The algorithm doesn't guarantee a full trim, but does guarantee we get smaller.
   */
  private void trim() {
    // sort the diffs
    double diffs[] = new double[nusedbins - 1];
    for (int i = 0; i < nusedbins - 1; i++) {
      diffs[i] = bins.get(i + 1).x - bins.get(i).x;
    }
    Arrays.sort(diffs);

    // We want to have nbins bins, so we want to keep the largest nbins-1 diffs
    double maxDiscardDiff = diffs[nusedbins - nbins];

    // If there are a lot of diffs == maxDiscardDiff, make sure we get rid of at
    // least one of them!  We delete others at ~50% probability.  We could count
    // the number of matching diffs and do a better job here, but :shrug:
    boolean deletedMaxDiscard = false;
    ArrayList<Coord> newBins = new ArrayList<>(nusedbins);

    Coord lastValid = bins.get(0);
    newBins.add(lastValid);
    for (int i = 1; i < nusedbins; i++) {
      Coord other = bins.get(i);
      double diff = other.x - lastValid.x;
      if (diff < maxDiscardDiff) {
        mergeBins(lastValid, other);
      } else if (diff == maxDiscardDiff && (!deletedMaxDiscard || prng.nextBoolean())) {
        mergeBins(lastValid, other);
        deletedMaxDiscard = true;
      } else {
        newBins.add(other);
        lastValid = other;
      }
    }
    bins = newBins;
    nusedbins = newBins.size();
  }

  private void mergeBins(Coord dest, Coord other) {
    // Merge the two bins into their average x location, weighted by their heights.
    // The height of the new bin is the sum of the heights of the old bins.
    double d = dest.y + other.y;
    dest.x = dest.x * dest.y / d;
    dest.x += other.x * other.y / d;
    dest.y = d;
  }

  /**
   * Gets an approximate quantile value from the current histogram. Some popular
   * quantiles are 0.5 (median), 0.95, and 0.98.
   *
   * @param q The requested quantile, must be strictly within the range (0,1).
   * @return The quantile value.
   */
  public double quantile(double q) {
    // The algorithm here is similar to that in UDAFPercentile.getPercentile.
    assert(bins != null && nusedbins > 0 && nbins > 0);
    double sum = 0;

    for (int b = 0; b < nusedbins; b++)  {
      sum += bins.get(b).y;
    }
    long n = Math.round(sum) - 1;
    double position = q * n;

    long lower = (long)Math.floor(position);
    long higher = (long)Math.ceil(position);

    // Linear search since this won't take much time from the total execution anyway
    // lower has the range of [0 .. total-1]
    // The first entry with accumulated count (lower+1) corresponds to the lower position.
    int i = 0;
    long csum = 0;
    while (i < nusedbins && (csum += Math.round(bins.get(i).y)) < lower + 1) {
      i++;
    }

    double lowerKey = bins.get(i).x;
    if (higher == lower) {
      // no interpolation needed because position does not have a fraction
      return lowerKey;
    }

    if (i < nusedbins && csum < higher + 1) {
      i++;
    }

    double higherKey = bins.get(i).x;

    if (higherKey == lowerKey) {
      // no interpolation needed because lower position and higher position has the same key
      return lowerKey;
    }

    // Linear interpolation to get the exact percentile
    return (higher - position) * lowerKey + (position - lower) * higherKey;
  }

  /**
   * In preparation for a Hive merge() call, serializes the current histogram object into an
   * ArrayList of DoubleWritable objects. This list is deserialized and merged by the
   * merge method.
   *
   * @return An ArrayList of Hadoop DoubleWritable objects that represents the current
   * histogram.
   * @see #merge
   */
  public ArrayList<DoubleWritable> serialize() {
    ArrayList<DoubleWritable> result = new ArrayList<DoubleWritable>();

    // Return a single ArrayList where the first element is the number of bins bins,
    // and subsequent elements represent bins (x,y) pairs.
    result.add(new DoubleWritable(nbins));
    if(bins != null) {
      for(int i = 0; i < nusedbins; i++) {
        result.add(new DoubleWritable(bins.get(i).x));
        result.add(new DoubleWritable(bins.get(i).y));
      }
    }

    return result;
  }

  public int getNumBins() {
    return bins == null ? 0 : bins.size();
  }
}
