// Copyright 2018 The Chromium Authors. All rights reserved.
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

package org.maplibre.maplibregl;

import android.Manifest;
import android.annotation.SuppressLint;
import android.content.Context;
import android.content.pm.PackageManager;
import android.content.res.AssetFileDescriptor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.PointF;
import android.graphics.RectF;
import android.location.Location;
import android.os.Build;
import android.util.DisplayMetrics;
import android.util.Log;
import android.util.Pair;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.TextureView;
import android.view.View;
import android.widget.FrameLayout;

import androidx.annotation.NonNull;
import androidx.lifecycle.DefaultLifecycleObserver;
import androidx.lifecycle.Lifecycle;
import androidx.lifecycle.LifecycleOwner;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;

import org.jetbrains.annotations.NotNull;
import org.maplibre.android.camera.CameraPosition;
import org.maplibre.android.camera.CameraUpdate;
import org.maplibre.android.camera.CameraUpdateFactory;
import org.maplibre.android.constants.MapLibreConstants;
import org.maplibre.android.geometry.LatLng;
import org.maplibre.android.geometry.LatLngBounds;
import org.maplibre.android.geometry.LatLngQuad;
import org.maplibre.android.geometry.VisibleRegion;
import org.maplibre.android.gestures.AndroidGesturesManager;
import org.maplibre.android.gestures.MoveGestureDetector;
import org.maplibre.android.location.LocationComponent;
import org.maplibre.android.location.LocationComponentActivationOptions;
import org.maplibre.android.location.LocationComponentOptions;
import org.maplibre.android.location.OnCameraTrackingChangedListener;
import org.maplibre.android.location.engine.LocationEngineCallback;
import org.maplibre.android.location.engine.LocationEngineRequest;
import org.maplibre.android.location.engine.LocationEngineResult;
import org.maplibre.android.location.modes.CameraMode;
import org.maplibre.android.location.modes.RenderMode;
import org.maplibre.android.maps.MapLibreMap;
import org.maplibre.android.maps.MapLibreMapOptions;
import org.maplibre.android.maps.MapView;
import org.maplibre.android.maps.OnMapReadyCallback;
import org.maplibre.android.maps.Style;
import org.maplibre.android.offline.OfflineManager;
import org.maplibre.android.style.expressions.Expression;
import org.maplibre.android.style.layers.CircleLayer;
import org.maplibre.android.style.layers.FillExtrusionLayer;
import org.maplibre.android.style.layers.FillLayer;
import org.maplibre.android.style.layers.HeatmapLayer;
import org.maplibre.android.style.layers.HillshadeLayer;
import org.maplibre.android.style.layers.Layer;
import org.maplibre.android.style.layers.LineLayer;
import org.maplibre.android.style.layers.Property;
import org.maplibre.android.style.layers.PropertyFactory;
import org.maplibre.android.style.layers.PropertyValue;
import org.maplibre.android.style.layers.RasterLayer;
import org.maplibre.android.style.layers.SymbolLayer;
import org.maplibre.android.style.sources.CustomGeometrySource;
import org.maplibre.android.style.sources.GeoJsonSource;
import org.maplibre.android.style.sources.ImageSource;
import org.maplibre.android.style.sources.Source;
import org.maplibre.android.style.sources.VectorSource;
import org.maplibre.geojson.Feature;
import org.maplibre.geojson.FeatureCollection;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import io.flutter.plugin.common.BinaryMessenger;
import io.flutter.plugin.common.MethodCall;
import io.flutter.plugin.common.MethodChannel;
import io.flutter.plugin.platform.PlatformView;


/** Controller of a single MapLibreMaps MapView instance. */
@SuppressLint("MissingPermission")
final class MapLibreMapController
    implements DefaultLifecycleObserver,
        MapLibreMap.OnCameraIdleListener,
        MapLibreMap.OnCameraMoveListener,
        MapLibreMap.OnCameraMoveStartedListener,
        MapView.OnDidBecomeIdleListener,
        MapLibreMap.OnMapClickListener,
        MapLibreMap.OnMapLongClickListener,
        MapLibreMapOptionsSink,
        MethodChannel.MethodCallHandler,
        OnMapReadyCallback,
        OnCameraTrackingChangedListener,
        PlatformView {
  private static final String TAG = "MapLibreMapController";
  private final int id;
  private final MethodChannel methodChannel;
  private final MapLibreMapsPlugin.LifecycleProvider lifecycleProvider;
  private final float density;
  private final Context context;
  private final String styleStringInitial;
  /**
   * This container is returned as the final platform view instead of returning `mapView`.
   * See {@link MapLibreMapController#destroyMapViewIfNecessary()} for details.
   */
  private FrameLayout mapViewContainer;
  private MapView mapView;
  private MapLibreMap mapLibreMap;
  private boolean trackCameraPosition = false;
  private boolean myLocationEnabled = false;
  private int myLocationTrackingMode = 0;
  private int myLocationRenderMode = 0;
  private LocationEngineFactory myLocationEngineFactory = new LocationEngineFactory();
  private boolean disposed = false;
  private boolean dragEnabled = true;
  private MethodChannel.Result mapReadyResult;
  private LocationComponent locationComponent = null;
  private LocationEngineCallback<LocationEngineResult> locationEngineCallback = null;
  private Style style;
  private Feature draggedFeature;
  private AndroidGesturesManager androidGesturesManager;

  private LatLng dragOrigin;
  private LatLng dragPrevious;

  private Set<String> interactiveFeatureLayerIds;
  private Map<String, FeatureCollection> addedFeaturesByLayer;

  private LatLngBounds bounds = null;
  Style.OnStyleLoaded onStyleLoadedCallback =
      new Style.OnStyleLoaded() {
        @Override
        public void onStyleLoaded(@NonNull Style style) {
          MapLibreMapController.this.style = style;

          // commented out while cherry-picking upstream956
          // if (myLocationEnabled) {
          //   if (hasLocationPermission()) {
          //     updateMyLocationEnabled();
          //   }
          // }
          updateMyLocationEnabled();

          if (null != bounds) {
            mapLibreMap.setLatLngBoundsForCameraTarget(bounds);
          }

          mapLibreMap.addOnMapClickListener(MapLibreMapController.this);
          mapLibreMap.addOnMapLongClickListener(MapLibreMapController.this);

          methodChannel.invokeMethod("map#onStyleLoaded", null);
        }
      };

  MapLibreMapController(
      int id,
      Context context,
      BinaryMessenger messenger,
      MapLibreMapsPlugin.LifecycleProvider lifecycleProvider,
      MapLibreMapOptions options,
      String styleStringInitial,
      boolean dragEnabled) {
    MapLibreUtils.getMapLibre(context);
    this.id = id;
    this.context = context;
    this.dragEnabled = dragEnabled;
    this.styleStringInitial = styleStringInitial;
    this.mapViewContainer = new FrameLayout(context);
    this.mapView = new MapView(context, options);
    this.interactiveFeatureLayerIds = new HashSet<>();
    this.addedFeaturesByLayer = new HashMap<String, FeatureCollection>();
    this.density = context.getResources().getDisplayMetrics().density;
    this.lifecycleProvider = lifecycleProvider;
    if (dragEnabled) {
      this.androidGesturesManager = new AndroidGesturesManager(this.mapView.getContext(), false);
    }

    mapViewContainer.addView(mapView);
    methodChannel = new MethodChannel(messenger, "plugins.flutter.io/maplibre_gl_" + id);
    methodChannel.setMethodCallHandler(this);
  }

  @Override
  public View getView() {
    return mapViewContainer;
  }

  void init() {
    lifecycleProvider.getLifecycle().addObserver(this);
    mapView.getMapAsync(this);
  }

  private void moveCamera(CameraUpdate cameraUpdate) {
    mapLibreMap.moveCamera(cameraUpdate);
  }

  private void animateCamera(CameraUpdate cameraUpdate) {
    mapLibreMap.animateCamera(cameraUpdate);
  }

  private CameraPosition getCameraPosition() {
    return trackCameraPosition ? mapLibreMap.getCameraPosition() : null;
  }

  @Override
  public void onMapReady(MapLibreMap mapLibreMap) {
    this.mapLibreMap = mapLibreMap;
    if (mapReadyResult != null) {
      mapReadyResult.success(null);
      mapReadyResult = null;
    }
    mapLibreMap.addOnCameraMoveStartedListener(this);
    mapLibreMap.addOnCameraMoveListener(this);
    mapLibreMap.addOnCameraIdleListener(this);

    if (androidGesturesManager != null) {
      androidGesturesManager.setMoveGestureListener(new MoveGestureListener());
      mapView.setOnTouchListener(
          new View.OnTouchListener() {
            @Override
            public boolean onTouch(View v, MotionEvent event) {
              androidGesturesManager.onTouchEvent(event);

              return draggedFeature != null;
            }
          });
    }

    mapView.addOnStyleImageMissingListener(
        (id) -> {
          DisplayMetrics displayMetrics = context.getResources().getDisplayMetrics();
          final Bitmap bitmap = getScaledImage(id, displayMetrics.density);
          if (bitmap != null) {
            mapLibreMap.getStyle().addImage(id, bitmap);
          }
        });

    mapView.addOnDidBecomeIdleListener(this);

    setStyleString(styleStringInitial);
  }

  @Override
  public void setStyleString(@NonNull String styleString) {
    // clear old layer id from the location Component
    clearLocationComponentLayer();
    styleString = styleString.trim();

    // Check if json, url, absolute path or asset path:
    if (styleString == null || styleString.isEmpty()) {
      Log.e(TAG, "setStyleString - string empty or null");
    } else if (styleString.startsWith("{") || styleString.startsWith("[")) {
      mapLibreMap.setStyle(new Style.Builder().fromJson(styleString), onStyleLoadedCallback);
    } else if (styleString.startsWith("/")) {
      // Absolute path
      mapLibreMap.setStyle(
          new Style.Builder().fromUri("file://" + styleString), onStyleLoadedCallback);
    } else if (!styleString.startsWith("http://")
        && !styleString.startsWith("https://")
        && !styleString.startsWith("mapbox://")) {
      // We are assuming that the style will be loaded from an asset here.
      String key = MapLibreMapsPlugin.flutterAssets.getAssetFilePathByName(styleString);
      mapLibreMap.setStyle(new Style.Builder().fromUri("asset://" + key), onStyleLoadedCallback);
    } else {
      mapLibreMap.setStyle(new Style.Builder().fromUri(styleString), onStyleLoadedCallback);
    }
  }



  @SuppressWarnings({"MissingPermission"})
  private void enableLocationComponent(@NonNull Style style) {
    if (hasLocationPermission()) {

      locationComponent = mapLibreMap.getLocationComponent();

      LocationComponentActivationOptions options =
              LocationComponentActivationOptions
                      .builder(context, style)
                      .locationComponentOptions(buildLocationComponentOptions(style))
                      .locationEngine(myLocationEngineFactory.getLocationEngine(context))
                      .build();

      locationComponent.activateLocationComponent(options);
      locationComponent.setLocationComponentEnabled(true);
      locationComponent.setMaxAnimationFps(30);
      updateMyLocationTrackingMode();
      updateMyLocationRenderMode();
      locationComponent.addOnCameraTrackingChangedListener(this);
    } else {
      Log.e(TAG, "missing location permissions");
    }
  }

  private void updateLocationComponentLayer() {
    if (locationComponent != null && locationComponentRequiresUpdate()) {
      locationComponent.applyStyle(buildLocationComponentOptions(style));
    }
  }

  private void clearLocationComponentLayer() {
    if (locationComponent != null) {
      locationComponent.applyStyle(buildLocationComponentOptions(null));
    }
  }

  String getLastLayerOnStyle(Style style) {
    if (style != null) {
      final List<Layer> layers = style.getLayers();

      if (layers.size() > 0) {
        return layers.get(layers.size() - 1).getId();
      }
    }
    return null;
  }

  /// only update if the last layer is not the mapbox-location-bearing-layer
  boolean locationComponentRequiresUpdate() {
    final String lastLayerId = getLastLayerOnStyle(style);
    return lastLayerId != null && !lastLayerId.equals("mapbox-location-bearing-layer");
  }

  private LocationComponentOptions buildLocationComponentOptions(Style style) {
    final LocationComponentOptions.Builder optionsBuilder =
        LocationComponentOptions.builder(context);
    optionsBuilder.trackingGesturesManagement(true);

    final String lastLayerId = getLastLayerOnStyle(style);
    if (lastLayerId != null) {
      optionsBuilder.layerAbove(lastLayerId);
    }
    return optionsBuilder.build();
  }

  private void onUserLocationUpdate(Location location) {
    if (location == null) {
      return;
    }

    final Map<String, Object> userLocation = new HashMap<>(6);
    userLocation.put("position", new double[] {location.getLatitude(), location.getLongitude()});
    userLocation.put("speed", location.getSpeed());
    userLocation.put("altitude", location.getAltitude());
    userLocation.put("bearing", location.getBearing());
    userLocation.put("speed", location.getSpeed());
    userLocation.put("horizontalAccuracy", location.getAccuracy());
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
      userLocation.put(
          "verticalAccuracy",
          (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
              ? location.getVerticalAccuracyMeters()
              : null);
    }
    userLocation.put("timestamp", location.getTime());

    final Map<String, Object> arguments = new HashMap<>(1);
    arguments.put("userLocation", userLocation);
    methodChannel.invokeMethod("map#onUserLocationUpdated", arguments);
  }

  private void addGeoJsonSource(String sourceName, String source) {
    FeatureCollection featureCollection = FeatureCollection.fromJson(source);
    GeoJsonSource geoJsonSource = new GeoJsonSource(sourceName, featureCollection);
    addedFeaturesByLayer.put(sourceName, featureCollection);

    style.addSource(geoJsonSource);
  }

  private void setGeoJsonSource(String sourceName, String geojson) {
    FeatureCollection featureCollection = FeatureCollection.fromJson(geojson);
    GeoJsonSource geoJsonSource = style.getSourceAs(sourceName);
    addedFeaturesByLayer.put(sourceName, featureCollection);

    geoJsonSource.setGeoJson(featureCollection);
  }

  private void setGeoJsonFeature(String sourceName, String geojsonFeature) {
    Feature feature = Feature.fromJson(geojsonFeature);
    FeatureCollection featureCollection = addedFeaturesByLayer.get(sourceName);
    GeoJsonSource geoJsonSource = style.getSourceAs(sourceName);
    if (featureCollection != null && geoJsonSource != null) {
      final List<Feature> features = featureCollection.features();
      for (int i = 0; i < features.size(); i++) {
        final String id = features.get(i).id();
        if (id.equals(feature.id())) {
          features.set(i, feature);
          break;
        }
      }

      geoJsonSource.setGeoJson(featureCollection);
    }
  }

  private void addSymbolLayer(
      String layerName,
      String sourceName,
      String belowLayerId,
      String sourceLayer,
      Float minZoom,
      Float maxZoom,
      PropertyValue[] properties,
      boolean enableInteraction,
      Expression filter) {
    SymbolLayer symbolLayer = new SymbolLayer(layerName, sourceName);
    symbolLayer.setProperties(properties);
    if (sourceLayer != null) {
      symbolLayer.setSourceLayer(sourceLayer);
    }
    if (minZoom != null) {
      symbolLayer.setMinZoom(minZoom);
    }
    if (maxZoom != null) {
      symbolLayer.setMaxZoom(maxZoom);
    }
    if (filter != null) {
      symbolLayer.setFilter(filter);
    }
    if (belowLayerId != null) {
      style.addLayerBelow(symbolLayer, belowLayerId);
    } else {
      style.addLayer(symbolLayer);
    }
    if (enableInteraction) {
      interactiveFeatureLayerIds.add(layerName);
    }
  }

  private void addLineLayer(
      String layerName,
      String sourceName,
      String belowLayerId,
      String sourceLayer,
      Float minZoom,
      Float maxZoom,
      PropertyValue[] properties,
      boolean enableInteraction,
      Expression filter) {
    LineLayer lineLayer = new LineLayer(layerName, sourceName);
    lineLayer.setProperties(properties);
    if (sourceLayer != null) {
      lineLayer.setSourceLayer(sourceLayer);
    }
    if (minZoom != null) {
      lineLayer.setMinZoom(minZoom);
    }
    if (maxZoom != null) {
      lineLayer.setMaxZoom(maxZoom);
    }
    if (filter != null) {
      lineLayer.setFilter(filter);
    }
    if (belowLayerId != null) {
      style.addLayerBelow(lineLayer, belowLayerId);
    } else {
      style.addLayer(lineLayer);
    }
    if (enableInteraction) {
      interactiveFeatureLayerIds.add(layerName);
    }
  }

  private void addFillLayer(
      String layerName,
      String sourceName,
      String belowLayerId,
      String sourceLayer,
      Float minZoom,
      Float maxZoom,
      PropertyValue[] properties,
      boolean enableInteraction,
      Expression filter) {
    FillLayer fillLayer = new FillLayer(layerName, sourceName);
    fillLayer.setProperties(properties);
    if (sourceLayer != null) {
      fillLayer.setSourceLayer(sourceLayer);
    }
    if (minZoom != null) {
      fillLayer.setMinZoom(minZoom);
    }
    if (maxZoom != null) {
      fillLayer.setMaxZoom(maxZoom);
    }
    if (filter != null) {
      fillLayer.setFilter(filter);
    }
    if (belowLayerId != null) {
      style.addLayerBelow(fillLayer, belowLayerId);
    } else {
      style.addLayer(fillLayer);
    }
    if (enableInteraction) {
      interactiveFeatureLayerIds.add(layerName);
    }
  }

  private void addFillExtrusionLayer(
          String layerName,
          String sourceName,
          String belowLayerId,
          String sourceLayer,
          Float minZoom,
          Float maxZoom,
          PropertyValue[] properties,
          boolean enableInteraction,
          Expression filter) {
    FillExtrusionLayer fillLayer = new FillExtrusionLayer(layerName, sourceName);
    fillLayer.setProperties(properties);
    if (sourceLayer != null) {
      fillLayer.setSourceLayer(sourceLayer);
    }
    if (minZoom != null) {
      fillLayer.setMinZoom(minZoom);
    }
    if (maxZoom != null) {
      fillLayer.setMaxZoom(maxZoom);
    }
    if (filter != null) {
      fillLayer.setFilter(filter);
    }
    if (belowLayerId != null) {
      style.addLayerBelow(fillLayer, belowLayerId);
    } else {
      style.addLayer(fillLayer);
    }
    if (enableInteraction) {
      interactiveFeatureLayerIds.add(layerName);
    }
  }

  private void addCircleLayer(
      String layerName,
      String sourceName,
      String belowLayerId,
      String sourceLayer,
      Float minZoom,
      Float maxZoom,
      PropertyValue[] properties,
      boolean enableInteraction,
      Expression filter) {
    CircleLayer circleLayer = new CircleLayer(layerName, sourceName);
    circleLayer.setProperties(properties);
    if (sourceLayer != null) {
      circleLayer.setSourceLayer(sourceLayer);
    }
    if (minZoom != null) {
      circleLayer.setMinZoom(minZoom);
    }
    if (maxZoom != null) {
      circleLayer.setMaxZoom(maxZoom);
    }
    if (filter != null) {
      circleLayer.setFilter(filter);
    }
    if (belowLayerId != null) {
      style.addLayerBelow(circleLayer, belowLayerId);
    } else {
      style.addLayer(circleLayer);
    }
    if (enableInteraction) {
      interactiveFeatureLayerIds.add(layerName);
    }
  }

  private Expression parseFilter(String filter) {
    JsonParser parser = new JsonParser();
    JsonElement filterJsonElement = parser.parse(filter);
    return filterJsonElement.isJsonNull() ? null : Expression.Converter.convert(filterJsonElement);
  }

  private void addRasterLayer(
      String layerName,
      String sourceName,
      Float minZoom,
      Float maxZoom,
      String belowLayerId,
      PropertyValue[] properties,
      Expression filter) {
    RasterLayer layer = new RasterLayer(layerName, sourceName);
    layer.setProperties(properties);
    if (minZoom != null) {
      layer.setMinZoom(minZoom);
    }
    if (maxZoom != null) {
      layer.setMaxZoom(maxZoom);
    }
    if (belowLayerId != null) {
      style.addLayerBelow(layer, belowLayerId);
    } else {
      style.addLayer(layer);
    }
  }

  private void addHillshadeLayer(
      String layerName,
      String sourceName,
      Float minZoom,
      Float maxZoom,
      String belowLayerId,
      PropertyValue[] properties,
      Expression filter) {
    HillshadeLayer layer = new HillshadeLayer(layerName, sourceName);
    layer.setProperties(properties);
    if (minZoom != null) {
      layer.setMinZoom(minZoom);
    }
    if (maxZoom != null) {
      layer.setMaxZoom(maxZoom);
    }
    if (belowLayerId != null) {
      style.addLayerBelow(layer, belowLayerId);
    } else {
      style.addLayer(layer);
    }
  }

  private void addHeatmapLayer(
      String layerName,
      String sourceName,
      Float minZoom,
      Float maxZoom,
      String belowLayerId,
      PropertyValue[] properties,
      Expression filter) {
    HeatmapLayer layer = new HeatmapLayer(layerName, sourceName);
    layer.setProperties(properties);
    if (minZoom != null) {
      layer.setMinZoom(minZoom);
    }
    if (maxZoom != null) {
      layer.setMaxZoom(maxZoom);
    }
    if (belowLayerId != null) {
      style.addLayerBelow(layer, belowLayerId);
    } else {
      style.addLayer(layer);
    }
  }

  private Pair<Feature, String> firstFeatureOnLayers(RectF in) {
    if (style != null) {
      final List<Layer> layers = style.getLayers();
      final List<String> layersInOrder = new ArrayList<String>();
      for (Layer layer : layers) {
        String id = layer.getId();
        if (interactiveFeatureLayerIds.contains(id)) layersInOrder.add(id);
      }
      Collections.reverse(layersInOrder);

      for (String id : layersInOrder) {
        List<Feature> features = mapLibreMap.queryRenderedFeatures(in, id);
        if (!features.isEmpty()) {
          return new Pair<Feature, String>(features.get(0), id);
        }
      }
    }
    return null;
  }

  @Override
  public void onMethodCall(MethodCall call, MethodChannel.Result result) {
    switch (call.method) {
      case "map#waitForMap":
        if (mapLibreMap != null) {
          result.success(null);
          return;
        }
        mapReadyResult = result;
        break;
      case "map#update":
        {
          Convert.interpretMapLibreMapOptions(call.argument("options"), this, context);
          result.success(Convert.toJson(getCameraPosition()));
          break;
        }
      case "map#updateMyLocationTrackingMode":
        {
          int myLocationTrackingMode = call.argument("mode");
          setMyLocationTrackingMode(myLocationTrackingMode);
          result.success(null);
          break;
        }
      case "map#matchMapLanguageWithDeviceDefault":
        {
          try {
            final Locale deviceLocale = Locale.getDefault();
            MapLibreMapUtils.setMapLanguage(mapLibreMap, deviceLocale.getLanguage());

            result.success(null);
          } catch (RuntimeException exception) {
            Log.d(TAG, exception.toString());
            result.error("MAPBOX LOCALIZATION PLUGIN ERROR", exception.toString(), null);
          }
          break;
        }
      case "map#updateContentInsets":
        {
          HashMap<String, Object> insets = call.argument("bounds");
          final CameraUpdate cameraUpdate =
              CameraUpdateFactory.paddingTo(
                  Convert.toPixels(insets.get("left"), density),
                  Convert.toPixels(insets.get("top"), density),
                  Convert.toPixels(insets.get("right"), density),
                  Convert.toPixels(insets.get("bottom"), density));

          if (call.argument("animated")) {
            animateCamera(cameraUpdate, null, result);
          } else {
            moveCamera(cameraUpdate, result);
          }
          break;
        }
      case "map#setMapLanguage":
        {
          final String language = call.argument("language");
          try {
            MapLibreMapUtils.setMapLanguage(mapLibreMap, language);

            result.success(null);
          } catch (RuntimeException exception) {
            Log.d(TAG, exception.toString());
            result.error("MAPBOX LOCALIZATION PLUGIN ERROR", exception.toString(), null);
          }
          break;
        }
      case "map#getVisibleRegion":
        {
          Map<String, Object> reply = new HashMap<>();
          VisibleRegion visibleRegion = mapLibreMap.getProjection().getVisibleRegion();
          reply.put(
              "sw",
              Arrays.asList(
                  visibleRegion.latLngBounds.getLatSouth(), visibleRegion.latLngBounds.getLonWest()));
          reply.put(
              "ne",
              Arrays.asList(
                    visibleRegion.latLngBounds.getLatNorth(), visibleRegion.latLngBounds.getLonEast()));

          result.success(reply);
          break;
        }
      case "map#toScreenLocation":
        {
          Map<String, Object> reply = new HashMap<>();
          PointF pointf =
              mapLibreMap
                  .getProjection()
                  .toScreenLocation(
                      new LatLng(call.argument("latitude"), call.argument("longitude")));
          reply.put("x", pointf.x);
          reply.put("y", pointf.y);
          result.success(reply);
          break;
        }
      case "map#toScreenLocationBatch":
        {
          double[] param = (double[]) call.argument("coordinates");
          double[] reply = new double[param.length];

          for (int i = 0; i < param.length; i += 2) {
            PointF pointf =
                mapLibreMap.getProjection().toScreenLocation(new LatLng(param[i], param[i + 1]));
            reply[i] = pointf.x;
            reply[i + 1] = pointf.y;
          }

          result.success(reply);
          break;
        }
      case "map#toLatLng":
        {
          Map<String, Object> reply = new HashMap<>();
          LatLng latlng =
              mapLibreMap
                  .getProjection()
                  .fromScreenLocation(
                      new PointF(
                          ((Double) call.argument("x")).floatValue(),
                          ((Double) call.argument("y")).floatValue()));
          reply.put("latitude", latlng.getLatitude());
          reply.put("longitude", latlng.getLongitude());
          result.success(reply);
          break;
        }
      case "map#getMetersPerPixelAtLatitude":
        {
          Map<String, Object> reply = new HashMap<>();
          Double retVal =
              mapLibreMap
                  .getProjection()
                  .getMetersPerPixelAtLatitude((Double) call.argument("latitude"));
          reply.put("metersperpixel", retVal);
          result.success(reply);
          break;
        }
      case "camera#move":
        {
          final CameraUpdate cameraUpdate =
              Convert.toCameraUpdate(call.argument("cameraUpdate"), mapLibreMap, density);
          if (cameraUpdate != null) {
            // camera transformation not handled yet
            mapLibreMap.moveCamera(
                cameraUpdate,
                new OnCameraMoveFinishedListener() {
                  @Override
                  public void onFinish() {
                    super.onFinish();
                    result.success(true);
                  }

                  @Override
                  public void onCancel() {
                    super.onCancel();
                    result.success(false);
                  }
                });

            // moveCamera(cameraUpdate);
          } else {
            result.success(false);
          }
          break;
        }
      case "camera#animate":
        {
          final CameraUpdate cameraUpdate =
              Convert.toCameraUpdate(call.argument("cameraUpdate"), mapLibreMap, density);
          final Integer duration = call.argument("duration");

          final OnCameraMoveFinishedListener onCameraMoveFinishedListener =
              new OnCameraMoveFinishedListener() {
                @Override
                public void onFinish() {
                  super.onFinish();
                  result.success(true);
                }

                @Override
                public void onCancel() {
                  super.onCancel();
                  result.success(false);
                }
              };
          if (cameraUpdate != null && duration != null) {
            // camera transformation not handled yet
            mapLibreMap.animateCamera(cameraUpdate, duration, onCameraMoveFinishedListener);
          } else if (cameraUpdate != null) {
            // camera transformation not handled yet
            mapLibreMap.animateCamera(cameraUpdate, onCameraMoveFinishedListener);
          } else {
            result.success(false);
          }
          break;
        }
      case "map#queryRenderedFeatures":
        {
          Map<String, Object> reply = new HashMap<>();
          List<Feature> features;

          String[] layerIds = ((List<String>) call.argument("layerIds")).toArray(new String[0]);

          List<Object> filter = call.argument("filter");
          JsonElement jsonElement = filter == null ? null : new Gson().toJsonTree(filter);
          JsonArray jsonArray = null;
          if (jsonElement != null && jsonElement.isJsonArray()) {
            jsonArray = jsonElement.getAsJsonArray();
          }
          Expression filterExpression =
              jsonArray == null ? null : Expression.Converter.convert(jsonArray);
          if (call.hasArgument("x")) {
            Double x = call.argument("x");
            Double y = call.argument("y");
            PointF pixel = new PointF(x.floatValue(), y.floatValue());
            features = mapLibreMap.queryRenderedFeatures(pixel, filterExpression, layerIds);
          } else {
            Double left = call.argument("left");
            Double top = call.argument("top");
            Double right = call.argument("right");
            Double bottom = call.argument("bottom");
            RectF rectF =
                new RectF(
                    left.floatValue(), top.floatValue(), right.floatValue(), bottom.floatValue());
            features = mapLibreMap.queryRenderedFeatures(rectF, filterExpression, layerIds);
          }
          List<String> featuresJson = new ArrayList<>();
          for (Feature feature : features) {
            featuresJson.add(feature.toJson());
          }
          reply.put("features", featuresJson);
          result.success(reply);
          break;
        }
      case "map#setTelemetryEnabled":
        {
          result.success(null);
          break;
        }
      case "map#getTelemetryEnabled":
        {
          result.success(false);
          break;
        }
      case "map#invalidateAmbientCache":
        {
          OfflineManager fileSource = OfflineManager.Companion.getInstance(context);

          fileSource.invalidateAmbientCache(
              new OfflineManager.FileSourceCallback() {
                @Override
                public void onSuccess() {
                  result.success(null);
                }

                @Override
                public void onError(@NonNull String message) {
                  result.error("MAPBOX CACHE ERROR", message, null);
                }
              });
          break;
        }
      case "map#clearAmbientCache":
      {
        OfflineManager fileSource = OfflineManager.Companion.getInstance(context);

        fileSource.clearAmbientCache(
                new OfflineManager.FileSourceCallback() {
                  @Override
                  public void onSuccess() {
                    result.success(null);
                  }

                  @Override
                  public void onError(@NonNull String message) {
                    result.error("MAPBOX CACHE ERROR", message, null);
                  }
                });
        break;
      }
      case "source#addGeoJson":
        {
          final String sourceId = call.argument("sourceId");
          final String geojson = call.argument("geojson");
          addGeoJsonSource(sourceId, geojson);
          result.success(null);
          break;
        }
      case "source#setGeoJson":
        {
          final String sourceId = call.argument("sourceId");
          final String geojson = call.argument("geojson");
          setGeoJsonSource(sourceId, geojson);
          result.success(null);
          break;
        }
      case "source#setFeature":
        {
          final String sourceId = call.argument("sourceId");
          final String geojsonFeature = call.argument("geojsonFeature");
          setGeoJsonFeature(sourceId, geojsonFeature);
          result.success(null);
          break;
        }
      case "symbolLayer#add":
        {
          final String sourceId = call.argument("sourceId");
          final String layerId = call.argument("layerId");
          final String belowLayerId = call.argument("belowLayerId");
          final String sourceLayer = call.argument("sourceLayer");
          final Double minzoom = call.argument("minzoom");
          final Double maxzoom = call.argument("maxzoom");
          final String filter = call.argument("filter");
          final boolean enableInteraction = call.argument("enableInteraction");
          final PropertyValue[] properties =
              LayerPropertyConverter.interpretSymbolLayerProperties(call.argument("properties"));

          Expression filterExpression = parseFilter(filter);

          addSymbolLayer(
              layerId,
              sourceId,
              belowLayerId,
              sourceLayer,
              minzoom != null ? minzoom.floatValue() : null,
              maxzoom != null ? maxzoom.floatValue() : null,
              properties,
              enableInteraction,
              filterExpression);
          updateLocationComponentLayer();

          result.success(null);
          break;
        }
      case "lineLayer#add":
        {
          final String sourceId = call.argument("sourceId");
          final String layerId = call.argument("layerId");
          final String belowLayerId = call.argument("belowLayerId");
          final String sourceLayer = call.argument("sourceLayer");
          final Double minzoom = call.argument("minzoom");
          final Double maxzoom = call.argument("maxzoom");
          final String filter = call.argument("filter");
          final boolean enableInteraction = call.argument("enableInteraction");
          final PropertyValue[] properties =
              LayerPropertyConverter.interpretLineLayerProperties(call.argument("properties"));

          Expression filterExpression = parseFilter(filter);

          addLineLayer(
              layerId,
              sourceId,
              belowLayerId,
              sourceLayer,
              minzoom != null ? minzoom.floatValue() : null,
              maxzoom != null ? maxzoom.floatValue() : null,
              properties,
              enableInteraction,
              filterExpression);
          updateLocationComponentLayer();

          result.success(null);
          break;
        }
        case "layer#setProperties": {
          final String layerId = call.argument("layerId");

          if (style == null) {
            result.error(
                "STYLE IS NULL",
                "The style is null. Has onStyleLoaded() already been invoked?",
                null);
          }

          Layer layer = style.getLayer(layerId);

          if (layer != null) {
            final PropertyValue[] properties;

            if (layer instanceof LineLayer) {
              properties = LayerPropertyConverter
                  .interpretLineLayerProperties(call.argument("properties"));
            } else if (layer instanceof FillLayer) {
              properties = LayerPropertyConverter
                  .interpretFillLayerProperties(call.argument("properties"));
            } else if (layer instanceof CircleLayer) {
              properties = LayerPropertyConverter
                  .interpretCircleLayerProperties(call.argument("properties"));
            } else if (layer instanceof SymbolLayer) {
              properties = LayerPropertyConverter
                  .interpretSymbolLayerProperties(call.argument("properties"));
            } else if (layer instanceof RasterLayer) {
              properties = LayerPropertyConverter
                  .interpretRasterLayerProperties(call.argument("properties"));
            } else if (layer instanceof HillshadeLayer) {
              properties = LayerPropertyConverter
                  .interpretHillshadeLayerProperties(call.argument("properties"));
            } else {
              result.error("UNSUPPORTED_LAYER_TYPE", "Layer type not supported", null);
              return;
            }
            layer.setProperties(properties);
            result.success(null);
          } else {
            result.error("LAYER_NOT_FOUND_ERROR", "Layer " + layerId + "not found", null);
          }

          break;
        }
      case "fillLayer#add":
        {
          final String sourceId = call.argument("sourceId");
          final String layerId = call.argument("layerId");
          final String belowLayerId = call.argument("belowLayerId");
          final String sourceLayer = call.argument("sourceLayer");
          final Double minzoom = call.argument("minzoom");
          final Double maxzoom = call.argument("maxzoom");
          final String filter = call.argument("filter");
          final boolean enableInteraction = call.argument("enableInteraction");
          final PropertyValue[] properties =
              LayerPropertyConverter.interpretFillLayerProperties(call.argument("properties"));

          Expression filterExpression = parseFilter(filter);

          addFillLayer(
              layerId,
              sourceId,
              belowLayerId,
              sourceLayer,
              minzoom != null ? minzoom.floatValue() : null,
              maxzoom != null ? maxzoom.floatValue() : null,
              properties,
              enableInteraction,
              filterExpression);
          updateLocationComponentLayer();

          result.success(null);
          break;
        }
      case "fillExtrusionLayer#add":
      {
        final String sourceId = call.argument("sourceId");
        final String layerId = call.argument("layerId");
        final String belowLayerId = call.argument("belowLayerId");
        final String sourceLayer = call.argument("sourceLayer");
        final Double minzoom = call.argument("minzoom");
        final Double maxzoom = call.argument("maxzoom");
        final String filter = call.argument("filter");
        final boolean enableInteraction = call.argument("enableInteraction");
        final PropertyValue[] properties =
                LayerPropertyConverter.interpretFillExtrusionLayerProperties(
                        call.argument("properties"));

        Expression filterExpression = parseFilter(filter);

        addFillExtrusionLayer(
                layerId,
                sourceId,
                belowLayerId,
                sourceLayer,
                minzoom != null ? minzoom.floatValue() : null,
                maxzoom != null ? maxzoom.floatValue() : null,
                properties,
                enableInteraction,
                filterExpression);
        updateLocationComponentLayer();

        result.success(null);
        break;
      }
      case "circleLayer#add":
        {
          final String sourceId = call.argument("sourceId");
          final String layerId = call.argument("layerId");
          final String belowLayerId = call.argument("belowLayerId");
          final String sourceLayer = call.argument("sourceLayer");
          final Double minzoom = call.argument("minzoom");
          final Double maxzoom = call.argument("maxzoom");
          final String filter = call.argument("filter");
          final boolean enableInteraction = call.argument("enableInteraction");
          final PropertyValue[] properties =
              LayerPropertyConverter.interpretCircleLayerProperties(call.argument("properties"));

          Expression filterExpression = parseFilter(filter);

          addCircleLayer(
              layerId,
              sourceId,
              belowLayerId,
              sourceLayer,
              minzoom != null ? minzoom.floatValue() : null,
              maxzoom != null ? maxzoom.floatValue() : null,
              properties,
              enableInteraction,
              filterExpression);
          updateLocationComponentLayer();

          result.success(null);
          break;
        }
      case "rasterLayer#add":
        {
          final String sourceId = call.argument("sourceId");
          final String layerId = call.argument("layerId");
          final String belowLayerId = call.argument("belowLayerId");
          final Double minzoom = call.argument("minzoom");
          final Double maxzoom = call.argument("maxzoom");
          final PropertyValue[] properties =
              LayerPropertyConverter.interpretRasterLayerProperties(call.argument("properties"));
          addRasterLayer(
              layerId,
              sourceId,
              minzoom != null ? minzoom.floatValue() : null,
              maxzoom != null ? maxzoom.floatValue() : null,
              belowLayerId,
              properties,
              null);
          updateLocationComponentLayer();

          result.success(null);
          break;
        }
      case "hillshadeLayer#add":
        {
          final String sourceId = call.argument("sourceId");
          final String layerId = call.argument("layerId");
          final String belowLayerId = call.argument("belowLayerId");
          final Double minzoom = call.argument("minzoom");
          final Double maxzoom = call.argument("maxzoom");
          final PropertyValue[] properties =
              LayerPropertyConverter.interpretHillshadeLayerProperties(call.argument("properties"));
          addHillshadeLayer(
              layerId,
              sourceId,
              minzoom != null ? minzoom.floatValue() : null,
              maxzoom != null ? maxzoom.floatValue() : null,
              belowLayerId,
              properties,
              null);
          updateLocationComponentLayer();

          result.success(null);
          break;
        }
      case "heatmapLayer#add":
        {
          final String sourceId = call.argument("sourceId");
          final String layerId = call.argument("layerId");
          final String belowLayerId = call.argument("belowLayerId");
          final Double minzoom = call.argument("minzoom");
          final Double maxzoom = call.argument("maxzoom");
          final PropertyValue[] properties =
              LayerPropertyConverter.interpretHeatmapLayerProperties(call.argument("properties"));
          addHeatmapLayer(
              layerId,
              sourceId,
              minzoom != null ? minzoom.floatValue() : null,
              maxzoom != null ? maxzoom.floatValue() : null,
              belowLayerId,
              properties,
              null);
          updateLocationComponentLayer();

          result.success(null);
          break;
        }
      case "locationComponent#getLastLocation":
        {
          Log.e(TAG, "location component: getLastLocation");
          if (this.myLocationEnabled
              && locationComponent != null
              && locationComponent.isLocationComponentActivated()
              && locationComponent.getLocationEngine() != null) {
            Map<String, Object> reply = new HashMap<>();

            mapLibreMap.getLocationComponent().getLocationEngine().getLastLocation(
                new LocationEngineCallback<LocationEngineResult>() {
                  @Override
                  public void onSuccess(LocationEngineResult locationEngineResult) {
                    Location lastLocation = locationEngineResult.getLastLocation();
                    if (lastLocation != null) {
                      reply.put("latitude", lastLocation.getLatitude());
                      reply.put("longitude", lastLocation.getLongitude());
                      reply.put("altitude", lastLocation.getAltitude());
                      result.success(reply);
                    } else {
                      result.error("", "", null); // ???
                    }
                  }

                  @Override
                  public void onFailure(@NonNull Exception exception) {
                    result.error("", "", null); // ???
                  }
                });
          } else {
            result.error(
                "LOCATION DISABLED",
                "Location is disabled or location component is unavailable",
                null);
          }
          break;
        }
      case "style#addImage":
        {
          if (style == null) {
            result.error(
                "STYLE IS NULL",
                "The style is null. Has onStyleLoaded() already been invoked?",
                null);
          }
          style.addImage(
              call.argument("name"),
              BitmapFactory.decodeByteArray(call.argument("bytes"), 0, call.argument("length")),
              call.argument("sdf"));
          result.success(null);
          break;
        }
      case "style#addImageSource":
        {
          if (style == null) {
            result.error(
                "STYLE IS NULL",
                "The style is null. Has onStyleLoaded() already been invoked?",
                null);
          }
          List<LatLng> coordinates = Convert.toLatLngList(call.argument("coordinates"), false);
          style.addSource(
              new ImageSource(
                  call.argument("imageSourceId"),
                  new LatLngQuad(
                      coordinates.get(0),
                      coordinates.get(1),
                      coordinates.get(2),
                      coordinates.get(3)),
                  BitmapFactory.decodeByteArray(
                      call.argument("bytes"), 0, call.argument("length"))));
          result.success(null);
          break;
        }
        case "style#updateImageSource":
        {
          if (style == null) {
            result.error(
                "STYLE IS NULL",
                "The style is null. Has onStyleLoaded() already been invoked?",
                null);
          }
          ImageSource imageSource = style.getSourceAs(call.argument("imageSourceId"));
          List<LatLng> coordinates = Convert.toLatLngList(call.argument("coordinates"), false);
          if (coordinates != null) {
            imageSource.setCoordinates(
                new LatLngQuad(
                    coordinates.get(0),
                    coordinates.get(1),
                    coordinates.get(2),
                    coordinates.get(3)));
          }
          byte[] bytes = call.argument("bytes");
          if (bytes != null) {
            imageSource.setImage(BitmapFactory.decodeByteArray(bytes, 0, call.argument("length")));
          }
          result.success(null);
          break;
        }
      case "style#addSource":
        {
          final String id = Convert.toString(call.argument("sourceId"));
          final Map<String, Object> properties = (Map<String, Object>) call.argument("properties");
          SourcePropertyConverter.addSource(id, properties, style);
          result.success(null);
          break;
        }

      case "style#removeSource":
        {
          if (style == null) {
            result.error(
                "STYLE IS NULL",
                "The style is null. Has onStyleLoaded() already been invoked?",
                null);
          }
          style.removeSource((String) call.argument("sourceId"));
          result.success(null);
          break;
        }
      case "style#addLayer":
        {
          if (style == null) {
            result.error(
                "STYLE IS NULL",
                "The style is null. Has onStyleLoaded() already been invoked?",
                null);
          }
          addRasterLayer(
              call.argument("imageLayerId"),
              call.argument("imageSourceId"),
              call.argument("minzoom") != null
                  ? ((Double) call.argument("minzoom")).floatValue()
                  : null,
              call.argument("maxzoom") != null
                  ? ((Double) call.argument("maxzoom")).floatValue()
                  : null,
              null,
              new PropertyValue[] {},
              null);
          result.success(null);
          break;
        }
      case "style#addLayerBelow":
        {
          if (style == null) {
            result.error(
                "STYLE IS NULL",
                "The style is null. Has onStyleLoaded() already been invoked?",
                null);
          }
          addRasterLayer(
              call.argument("imageLayerId"),
              call.argument("imageSourceId"),
              call.argument("minzoom") != null
                  ? ((Double) call.argument("minzoom")).floatValue()
                  : null,
              call.argument("maxzoom") != null
                  ? ((Double) call.argument("maxzoom")).floatValue()
                  : null,
              call.argument("belowLayerId"),
              new PropertyValue[] {},
              null);
          result.success(null);
          break;
        }
      case "style#removeLayer":
        {
          if (style == null) {
            result.error(
                "STYLE IS NULL",
                "The style is null. Has onStyleLoaded() already been invoked?",
                null);
          }
          String layerId = call.argument("layerId");
          style.removeLayer(layerId);
          interactiveFeatureLayerIds.remove(layerId);

          result.success(null);
          break;
        }
      case "map#setCameraBounds":
        {
          double west = call.argument("west");
          double north = call.argument("north");
          double south = call.argument("south");
          double east = call.argument("east");

          int padding = call.argument("padding");

          LatLng locationOne = new LatLng(north, east);
          LatLng locationTwo = new LatLng(south, west);
          LatLngBounds latLngBounds = new LatLngBounds.Builder()
                  .include(locationOne) // Northeast
                  .include(locationTwo) // Southwest
                  .build();
          mapLibreMap.easeCamera(CameraUpdateFactory.newLatLngBounds(latLngBounds,
                  padding), 200);

          break;
        }
      case "style#setFilter":
        {
          if (style == null) {
            result.error(
                "STYLE IS NULL",
                "The style is null. Has onStyleLoaded() already been invoked?",
                null);
          }
          String layerId = call.argument("layerId");
          String filter = call.argument("filter");

          Layer layer = style.getLayer(layerId);

          JsonParser parser = new JsonParser();
          JsonElement jsonElement = parser.parse(filter);
          Expression expression = Expression.Converter.convert(jsonElement);

          if (layer instanceof CircleLayer) {
            ((CircleLayer) layer).setFilter(expression);
          } else if (layer instanceof FillExtrusionLayer) {
            ((FillExtrusionLayer) layer).setFilter(expression);
          } else if (layer instanceof FillLayer) {
            ((FillLayer) layer).setFilter(expression);
          } else if (layer instanceof HeatmapLayer) {
            ((HeatmapLayer) layer).setFilter(expression);
          } else if (layer instanceof LineLayer) {
            ((LineLayer) layer).setFilter(expression);
          } else if (layer instanceof SymbolLayer) {
            ((SymbolLayer) layer).setFilter(expression);
          } else {
            result.error(
                "INVALID LAYER TYPE",
                String.format("Layer '%s' does not support filtering.", layerId),
                null);
            break;
          }

          result.success(null);
          break;
        }
        case "style#getFilter":
        {
          if (style == null) {
            result.error(
                    "STYLE IS NULL",
                    "The style is null. Has onStyleLoaded() already been invoked?",
                    null);
          }
          Map<String, Object> reply = new HashMap<>();
          String layerId = call.argument("layerId");
          Layer layer = style.getLayer(layerId);

          Expression filter;
          if (layer instanceof CircleLayer) {
            filter = ((CircleLayer) layer).getFilter();
          } else if (layer instanceof FillExtrusionLayer) {
            filter = ((FillExtrusionLayer) layer).getFilter();
          } else if (layer instanceof FillLayer) {
            filter = ((FillLayer) layer).getFilter();
          } else if (layer instanceof HeatmapLayer) {
            filter = ((HeatmapLayer) layer).getFilter();
          } else if (layer instanceof LineLayer) {
            filter = ((LineLayer) layer).getFilter();
          } else if (layer instanceof SymbolLayer) {
            filter = ((SymbolLayer) layer).getFilter();
          } else {
            result.error(
                    "INVALID LAYER TYPE",
                    String.format("Layer '%s' does not support filtering.", layerId),
                    null);
            break;
          }

          reply.put("filter", filter.toString());
          result.success(reply);
          break;
        }
        case "layer#setVisibility":
        {

          if (style == null) {
            result.error(
                "STYLE IS NULL",
                "The style is null. Has onStyleLoaded() already been invoked?",
                null);
          }
          String layerId = call.argument("layerId");
          boolean visible = call.argument("visible");

          Layer layer = style.getLayer(layerId);

          if (layer != null) {
            layer.setProperties(PropertyFactory.visibility(visible ? Property.VISIBLE : Property.NONE));
          }

          result.success(null);
          break;

        }
        case "map#querySourceFeatures":
        {
          Map<String, Object> reply = new HashMap<>();
          List<Feature> features;

          String sourceId = (String) call.argument("sourceId");

          String sourceLayerId = (String) call.argument("sourceLayerId");

          List<Object> filter = call.argument("filter");
          JsonElement jsonElement = filter == null ? null : new Gson().toJsonTree(filter);
          JsonArray jsonArray = null;
          if (jsonElement != null && jsonElement.isJsonArray()) {
            jsonArray = jsonElement.getAsJsonArray();
          }
          Expression filterExpression =
                  jsonArray == null ? null : Expression.Converter.convert(jsonArray);


          Source source = style.getSource(sourceId);
          if (source instanceof GeoJsonSource) {
            features = ((GeoJsonSource) source).querySourceFeatures(filterExpression);
          } else if (source instanceof CustomGeometrySource) {
            features = ((CustomGeometrySource) source).querySourceFeatures(filterExpression);
          } else if (source instanceof VectorSource && sourceLayerId != null) {
            features = ((VectorSource) source).querySourceFeatures(new String[] {sourceLayerId}, filterExpression);
          } else {
            features = Collections.emptyList();
          }

          List<String> featuresJson = new ArrayList<>();
          for (Feature feature : features) {
            featuresJson.add(feature.toJson());
          }
          reply.put("features", featuresJson);
          result.success(reply);
          break;
        }
        case "style#getLayerIds":
        {
          if (style == null) {
            result.error(
                    "STYLE IS NULL",
                    "The style is null. Has onStyleLoaded() already been invoked?",
                    null);
          }
          Map<String, Object> reply = new HashMap<>();

          List<String> layerIds = new ArrayList<>();
          for (Layer layer : style.getLayers()) {
            layerIds.add(layer.getId());
          }

          reply.put("layers", layerIds);
          result.success(reply);
          break;
        }
      case "style#getSourceIds":
      {
        if (style == null) {
          result.error(
                  "STYLE IS NULL",
                  "The style is null. Has onStyleLoaded() already been invoked?",
                  null);
        }
        Map<String, Object> reply = new HashMap<>();

        List<String> sourceIds = new ArrayList<>();
        for (Source source : style.getSources()) {
          sourceIds.add(source.getId());
        }

        reply.put("sources", sourceIds);
        result.success(reply);
        break;
      }
      default:
        result.notImplemented();
    }
  }

  @Override
  public void onCameraMoveStarted(int reason) {
    final Map<String, Object> arguments = new HashMap<>(2);
    boolean isGesture = reason == MapLibreMap.OnCameraMoveStartedListener.REASON_API_GESTURE;
    arguments.put("isGesture", isGesture);
    methodChannel.invokeMethod("camera#onMoveStarted", arguments);
  }

  @Override
  public void onCameraMove() {
    if (!trackCameraPosition) {
      return;
    }
    final Map<String, Object> arguments = new HashMap<>(2);
    arguments.put("position", Convert.toJson(mapLibreMap.getCameraPosition()));
    methodChannel.invokeMethod("camera#onMove", arguments);
  }

  @Override
  public void onCameraIdle() {
    final Map<String, Object> arguments = new HashMap<>(2);
    if (trackCameraPosition) {
      arguments.put("position", Convert.toJson(mapLibreMap.getCameraPosition()));
    }
    methodChannel.invokeMethod("camera#onIdle", arguments);
  }

  @Override
  public void onCameraTrackingChanged(int currentMode) {
    final Map<String, Object> arguments = new HashMap<>(2);
    switch (currentMode) {
        case CameraMode.NONE:
            arguments.put("mode", 0);
            break;
        case CameraMode.TRACKING:
            arguments.put("mode", 1);
            break;
        case CameraMode.TRACKING_COMPASS:
            arguments.put("mode", 2);
            break;
        case CameraMode.TRACKING_GPS:
            arguments.put("mode", 3);
            break;
        default:
            Log.e(TAG, "Unable to map " + currentMode + " to a tracking mode");
            return;
    }

    methodChannel.invokeMethod("map#onCameraTrackingChanged", arguments);
  }

  @Override
  public void onCameraTrackingDismissed() {
    this.myLocationTrackingMode = 0;
    methodChannel.invokeMethod("map#onCameraTrackingDismissed", new HashMap<>());
  }

  @Override
  public void onDidBecomeIdle() {
    methodChannel.invokeMethod("map#onIdle", new HashMap<>());
  }

  @Override
  public boolean onMapClick(@NonNull LatLng point) {
    PointF pointf = mapLibreMap.getProjection().toScreenLocation(point);
    RectF rectF = new RectF(pointf.x - 10, pointf.y - 10, pointf.x + 10, pointf.y + 10);
    Pair<Feature, String> featureLayerPair = firstFeatureOnLayers(rectF);
    final Map<String, Object> arguments = new HashMap<>();
    arguments.put("x", pointf.x);
    arguments.put("y", pointf.y);
    arguments.put("lng", point.getLongitude());
    arguments.put("lat", point.getLatitude());
    if (featureLayerPair != null && featureLayerPair.first != null) {
      arguments.put("layerId", featureLayerPair.second);
      arguments.put("id", featureLayerPair.first.id());
      methodChannel.invokeMethod("feature#onTap", arguments);
    } else {
      methodChannel.invokeMethod("map#onMapClick", arguments);
    }
    return true;
  }

  @Override
  public boolean onMapLongClick(@NonNull LatLng point) {
    PointF pointf = mapLibreMap.getProjection().toScreenLocation(point);
    final Map<String, Object> arguments = new HashMap<>(5);
    arguments.put("x", pointf.x);
    arguments.put("y", pointf.y);
    arguments.put("lng", point.getLongitude());
    arguments.put("lat", point.getLatitude());
    methodChannel.invokeMethod("map#onMapLongClick", arguments);
    return true;
  }

  @Override
  public void dispose() {
    if (disposed) {
      return;
    }
    disposed = true;
    methodChannel.setMethodCallHandler(null);
    destroyMapViewIfNecessary();
    Lifecycle lifecycle = lifecycleProvider.getLifecycle();
    if (lifecycle != null) {
      lifecycle.removeObserver(this);
    }
  }

  private void moveCamera(CameraUpdate cameraUpdate, MethodChannel.Result result) {
    if (cameraUpdate != null) {
      // camera transformation not handled yet
      mapLibreMap.moveCamera(
          cameraUpdate,
          new OnCameraMoveFinishedListener() {
            @Override
            public void onFinish() {
              super.onFinish();
              result.success(true);
            }

            @Override
            public void onCancel() {
              super.onCancel();
              result.success(false);
            }
          });

      // moveCamera(cameraUpdate);
    } else {
      result.success(false);
    }
  }

  private void animateCamera(
      CameraUpdate cameraUpdate, Integer duration, MethodChannel.Result result) {
    final OnCameraMoveFinishedListener onCameraMoveFinishedListener =
        new OnCameraMoveFinishedListener() {
          @Override
          public void onFinish() {
            super.onFinish();
            result.success(true);
          }

          @Override
          public void onCancel() {
            super.onCancel();
            result.success(false);
          }
        };
    if (cameraUpdate != null && duration != null) {
      // camera transformation not handled yet
      mapLibreMap.animateCamera(cameraUpdate, duration, onCameraMoveFinishedListener);
    } else if (cameraUpdate != null) {
      // camera transformation not handled yet
      mapLibreMap.animateCamera(cameraUpdate, onCameraMoveFinishedListener);
    } else {
      result.success(false);
    }
  }

  /**
   * Destroy the MapView and cleans up listeners.
   * It's very important to call mapViewContainer.removeView(mapView) to make sure
   * that {@link TextureView#onDetachedFromWindowInternal()} is called which releases the
   * underlying surface.
   * This is required due to an FlutterEngine change that was introduce when updating from
   * Flutter 2.10.5 to Flutter 3.10.0.
   * This FlutterEngine change is not calling `removeView` on a PlatformView which causes the issue.
   * <p>
   * For more information check out:
   * <a href="https://github.com/flutter/flutter/issues/107297">Flutter issue</a>
   * <a href="https://github.com/flutter/engine/commit/8dc7cd1b1a33b5da561ac859cdcc49705ad1e598">Flutter Engine commit that introduced the issue</a>
   * <a href="https://github.com/maplibre/flutter-maplibre-gl/issues/182">The reported issue in the MapLibre repo</a>
   */
  private void destroyMapViewIfNecessary() {
    if (mapView == null) {
      return;
    }

    if (locationComponent != null) {
      locationComponent.setLocationComponentEnabled(false);
    }
    stopListeningForLocationUpdates();

    mapViewContainer.removeView(mapView);

    mapView.onStop();
    mapView.onDestroy();

    mapView = null;
  }

  @Override
  public void onCreate(@NonNull LifecycleOwner owner) {
    if (disposed) {
      return;
    }
    mapView.onCreate(null);
  }

  @Override
  public void onStart(@NonNull LifecycleOwner owner) {
    if (disposed) {
      return;
    }
    mapView.onStart();
  }

  @Override
  public void onResume(@NonNull LifecycleOwner owner) {
    if (disposed) {
      return;
    }
    mapView.onResume();
    if (myLocationEnabled) {
      startListeningForLocationUpdates();
    }
  }

  @Override
  public void onPause(@NonNull LifecycleOwner owner) {
    if (disposed) {
      return;
    }
    mapView.onPause();
  }

  @Override
  public void onStop(@NonNull LifecycleOwner owner) {
    if (disposed) {
      return;
    }
    mapView.onStop();
  }

  @Override
  public void onDestroy(@NonNull LifecycleOwner owner) {
    owner.getLifecycle().removeObserver(this);
    if (disposed) {
      return;
    }
    destroyMapViewIfNecessary();
  }

  // MapLibreMapOptionsSink methods

  @Override
  public void setCameraTargetBounds(LatLngBounds bounds) {
    this.bounds = bounds;
  }

  @Override
  public void setLocationEngineProperties(@NotNull LocationEngineRequest locationEngineRequest) {
    myLocationEngineFactory.initLocationComponent(context, locationComponent, locationEngineRequest);
  }

  @Override
  public void setCompassEnabled(boolean compassEnabled) {
    mapLibreMap.getUiSettings().setCompassEnabled(compassEnabled);
  }

  @Override
  public void setTrackCameraPosition(boolean trackCameraPosition) {
    this.trackCameraPosition = trackCameraPosition;
  }

  @Override
  public void setRotateGesturesEnabled(boolean rotateGesturesEnabled) {
    mapLibreMap.getUiSettings().setRotateGesturesEnabled(rotateGesturesEnabled);
  }

  @Override
  public void setScrollGesturesEnabled(boolean scrollGesturesEnabled) {
    mapLibreMap.getUiSettings().setScrollGesturesEnabled(scrollGesturesEnabled);
  }

  @Override
  public void setTiltGesturesEnabled(boolean tiltGesturesEnabled) {
    mapLibreMap.getUiSettings().setTiltGesturesEnabled(tiltGesturesEnabled);
  }

  @Override
  public void setMinMaxZoomPreference(Float min, Float max) {
    mapLibreMap.setMinZoomPreference(min != null ? min : MapLibreConstants.MINIMUM_ZOOM);
    mapLibreMap.setMaxZoomPreference(max != null ? max : MapLibreConstants.MAXIMUM_ZOOM);
  }

  @Override
  public void setZoomGesturesEnabled(boolean zoomGesturesEnabled) {
    mapLibreMap.getUiSettings().setZoomGesturesEnabled(zoomGesturesEnabled);
  }

  @Override
  public void setMyLocationEnabled(boolean myLocationEnabled) {
    if (this.myLocationEnabled == myLocationEnabled) {
      return;
    }
    this.myLocationEnabled = myLocationEnabled;
    if (mapLibreMap != null) {
      updateMyLocationEnabled();
    }
  }

  @Override
  public void setMyLocationTrackingMode(int myLocationTrackingMode) {
    if (mapLibreMap != null) {
      // ensure that location is trackable
      updateMyLocationEnabled();
    }
    if (this.myLocationTrackingMode == myLocationTrackingMode) {
      return;
    }
    this.myLocationTrackingMode = myLocationTrackingMode;
    if (mapLibreMap != null && locationComponent != null) {
      updateMyLocationTrackingMode();
    }
  }

  @Override
  public void setMyLocationRenderMode(int myLocationRenderMode) {
    if (this.myLocationRenderMode == myLocationRenderMode) {
      return;
    }
    this.myLocationRenderMode = myLocationRenderMode;
    if (mapLibreMap != null && locationComponent != null) {
      updateMyLocationRenderMode();
    }
  }

  public void setLogoViewMargins(int x, int y) {
    mapLibreMap.getUiSettings().setLogoMargins(x, 0, 0, y);
  }

  @Override
  public void setCompassGravity(int gravity) {
    switch (gravity) {
      case 0:
        mapLibreMap.getUiSettings().setCompassGravity(Gravity.TOP | Gravity.START);
        break;
      default:
      case 1:
        mapLibreMap.getUiSettings().setCompassGravity(Gravity.TOP | Gravity.END);
        break;
      case 2:
        mapLibreMap.getUiSettings().setCompassGravity(Gravity.BOTTOM | Gravity.START);
        break;
      case 3:
        mapLibreMap.getUiSettings().setCompassGravity(Gravity.BOTTOM | Gravity.END);
        break;
    }
  }

  @Override
  public void setCompassViewMargins(int x, int y) {
    switch (mapLibreMap.getUiSettings().getCompassGravity()) {
      case Gravity.TOP | Gravity.START:
        mapLibreMap.getUiSettings().setCompassMargins(x, y, 0, 0);
        break;
      default:
      case Gravity.TOP | Gravity.END:
        mapLibreMap.getUiSettings().setCompassMargins(0, y, x, 0);
        break;
      case Gravity.BOTTOM | Gravity.START:
        mapLibreMap.getUiSettings().setCompassMargins(x, 0, 0, y);
        break;
      case Gravity.BOTTOM | Gravity.END:
        mapLibreMap.getUiSettings().setCompassMargins(0, 0, x, y);
        break;
    }
  }

  @Override
  public void setAttributionButtonGravity(int gravity) {
    switch (gravity) {
      case 0:
        mapLibreMap.getUiSettings().setAttributionGravity(Gravity.TOP | Gravity.START);
        break;
      default:
      case 1:
        mapLibreMap.getUiSettings().setAttributionGravity(Gravity.TOP | Gravity.END);
        break;
      case 2:
        mapLibreMap.getUiSettings().setAttributionGravity(Gravity.BOTTOM | Gravity.START);
        break;
      case 3:
        mapLibreMap.getUiSettings().setAttributionGravity(Gravity.BOTTOM | Gravity.END);
        break;
    }
  }

  @Override
  public void setAttributionButtonMargins(int x, int y) {
    switch (mapLibreMap.getUiSettings().getAttributionGravity()) {
      case Gravity.TOP | Gravity.START:
        mapLibreMap.getUiSettings().setAttributionMargins(x, y, 0, 0);
        break;
      default:
      case Gravity.TOP | Gravity.END:
        mapLibreMap.getUiSettings().setAttributionMargins(0, y, x, 0);
        break;
      case Gravity.BOTTOM | Gravity.START:
        mapLibreMap.getUiSettings().setAttributionMargins(x, 0, 0, y);
        break;
      case Gravity.BOTTOM | Gravity.END:
        mapLibreMap.getUiSettings().setAttributionMargins(0, 0, x, y);
        break;
    }
  }

  private void updateMyLocationEnabled() {
    if (this.locationComponent == null && mapLibreMap.getStyle() != null && myLocationEnabled) {
      enableLocationComponent(mapLibreMap.getStyle());
    }

    if (myLocationEnabled) {
      startListeningForLocationUpdates();
    } else {
      stopListeningForLocationUpdates();
    }

    if (locationComponent != null) {
      locationComponent.setLocationComponentEnabled(myLocationEnabled);
    }
  }

  private void startListeningForLocationUpdates() {
    if (locationEngineCallback == null
        && locationComponent != null
        && locationComponent.isLocationComponentActivated()
        && locationComponent.getLocationEngine() != null) {
      locationEngineCallback =
          new LocationEngineCallback<LocationEngineResult>() {
            @Override
            public void onSuccess(LocationEngineResult result) {
              onUserLocationUpdate(result.getLastLocation());
            }

            @Override
            public void onFailure(@NonNull Exception exception) {}
          };
      locationComponent
          .getLocationEngine()
          .requestLocationUpdates(
              locationComponent.getLocationEngineRequest(), locationEngineCallback, null);
    }
  }

  private void stopListeningForLocationUpdates() {
    if (locationEngineCallback != null
        && locationComponent != null
        && locationComponent.isLocationComponentActivated()
        && locationComponent.getLocationEngine() != null) {
      locationComponent.getLocationEngine().removeLocationUpdates(locationEngineCallback);
      locationEngineCallback = null;
    }
  }

  private void updateMyLocationTrackingMode() {
    int[] mapboxTrackingModes =
        new int[] {
          CameraMode.NONE, CameraMode.TRACKING, CameraMode.TRACKING_COMPASS, CameraMode.TRACKING_GPS
        };
    locationComponent.setCameraMode(mapboxTrackingModes[this.myLocationTrackingMode]);
  }

  private void updateMyLocationRenderMode() {
    int[] mapboxRenderModes = new int[] {RenderMode.NORMAL, RenderMode.COMPASS, RenderMode.GPS};
    locationComponent.setRenderMode(mapboxRenderModes[this.myLocationRenderMode]);
  }

  private boolean hasLocationPermission() {
    return checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)
            == PackageManager.PERMISSION_GRANTED
        || checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION)
            == PackageManager.PERMISSION_GRANTED;
  }

  private int checkSelfPermission(String permission) {
    if (permission == null) {
      throw new IllegalArgumentException("permission is null");
    }
    return context.checkPermission(
        permission, android.os.Process.myPid(), android.os.Process.myUid());
  }

  /**
   * Tries to find highest scale image for display type
   *
   * @param imageId
   * @param density
   * @return
   */
  private Bitmap getScaledImage(String imageId, float density) {
    AssetFileDescriptor assetFileDescriptor;

    // Split image path into parts.
    List<String> imagePathList = Arrays.asList(imageId.split("/"));
    List<String> assetPathList = new ArrayList<>();

    // "On devices with a device pixel ratio of 1.8, the asset .../2.0x/my_icon.png would be chosen.
    // For a device pixel ratio of 2.7, the asset .../3.0x/my_icon.png would be chosen."
    // Source: https://flutter.dev/docs/development/ui/assets-and-images#resolution-aware
    for (int i = (int) Math.ceil(density); i > 0; i--) {
      String assetPath;
      if (i == 1) {
        // If density is 1.0x then simply take the default asset path
        assetPath = MapLibreMapsPlugin.flutterAssets.getAssetFilePathByName(imageId);
      } else {
        // Build a resolution aware asset path as follows:
        // <directory asset>/<ratio>/<image name>
        // where ratio is 1.0x, 2.0x or 3.0x.
        StringBuilder stringBuilder = new StringBuilder();
        for (int j = 0; j < imagePathList.size() - 1; j++) {
          stringBuilder.append(imagePathList.get(j));
          stringBuilder.append("/");
        }
        stringBuilder.append(((float) i) + "x");
        stringBuilder.append("/");
        stringBuilder.append(imagePathList.get(imagePathList.size() - 1));
        assetPath = MapLibreMapsPlugin.flutterAssets.getAssetFilePathByName(stringBuilder.toString());
      }
      // Build up a list of resolution aware asset paths.
      assetPathList.add(assetPath);
    }

    // Iterate over asset paths and get the highest scaled asset (as a bitmap).
    Bitmap bitmap = null;
    for (String assetPath : assetPathList) {
      try {
        // Read path (throws exception if doesn't exist).
        assetFileDescriptor = mapView.getContext().getAssets().openFd(assetPath);
        InputStream assetStream = assetFileDescriptor.createInputStream();
        bitmap = BitmapFactory.decodeStream(assetStream);
        assetFileDescriptor.close(); // Close for memory
        break; // If exists, break
      } catch (IOException e) {
        // Skip
      }
    }
    return bitmap;
  }

  boolean onMoveBegin(MoveGestureDetector detector) {
    // onMoveBegin gets called even during a move - move end is also not called unless this function
    // returns
    // true at least once. To avoid redundant queries only check for feature if the previous event
    // was ACTION_DOWN
    if (detector.getPreviousEvent().getActionMasked() == MotionEvent.ACTION_DOWN
        && detector.getPointersCount() == 1) {
      PointF pointf = detector.getFocalPoint();
      LatLng origin = mapLibreMap.getProjection().fromScreenLocation(pointf);
      RectF rectF = new RectF(pointf.x - 10, pointf.y - 10, pointf.x + 10, pointf.y + 10);
      Pair<Feature, String> featureLayerPair = firstFeatureOnLayers(rectF);
      if (featureLayerPair != null && featureLayerPair.first != null && startDragging(featureLayerPair.first, origin)) {
        invokeFeatureDrag(pointf, "start");
        return true;
      }
    }
    return false;
  }

  private void invokeFeatureDrag(PointF pointf, String eventType) {
    LatLng current = mapLibreMap.getProjection().fromScreenLocation(pointf);

    final Map<String, Object> arguments = new HashMap<>(9);
    arguments.put("id", draggedFeature.id());
    arguments.put("x", pointf.x);
    arguments.put("y", pointf.y);
    arguments.put("originLng", dragOrigin.getLongitude());
    arguments.put("originLat", dragOrigin.getLatitude());
    arguments.put("currentLng", current.getLongitude());
    arguments.put("currentLat", current.getLatitude());
    arguments.put("eventType", eventType);
    arguments.put("deltaLng", current.getLongitude() - dragPrevious.getLongitude());
    arguments.put("deltaLat", current.getLatitude() - dragPrevious.getLatitude());
    dragPrevious = current;
    methodChannel.invokeMethod("feature#onDrag", arguments);
  }

  boolean onMove(MoveGestureDetector detector) {
    if (draggedFeature != null) {
      if (detector.getPointersCount() > 1) {
        stopDragging();
        return true;
      }
      PointF pointf = detector.getFocalPoint();
      invokeFeatureDrag(pointf, "drag");
      return false;
    }
    return true;
  }

  void onMoveEnd(MoveGestureDetector detector) {
    PointF pointf = detector.getFocalPoint();
    invokeFeatureDrag(pointf, "end");
    stopDragging();
  }

  boolean startDragging(@NonNull Feature feature, @NonNull LatLng origin) {
    final boolean draggable =
        feature.hasNonNullValueForProperty("draggable")
            ? feature.getBooleanProperty("draggable")
            : false;
    if (draggable) {
      draggedFeature = feature;
      dragPrevious = origin;
      dragOrigin = origin;
      return true;
    }
    return false;
  }

  void stopDragging() {
    draggedFeature = null;
    dragOrigin = null;
    dragPrevious = null;
  }

  /** Simple Listener to listen for the status of camera movements. */
  public class OnCameraMoveFinishedListener implements MapLibreMap.CancelableCallback {
    @Override
    public void onFinish() {}

    @Override
    public void onCancel() {}
  }

  private class MoveGestureListener implements MoveGestureDetector.OnMoveGestureListener {

    @Override
    public boolean onMoveBegin(MoveGestureDetector detector) {
      return MapLibreMapController.this.onMoveBegin(detector);
    }

    @Override
    public boolean onMove(MoveGestureDetector detector, float distanceX, float distanceY) {
      return MapLibreMapController.this.onMove(detector);
    }

    @Override
    public void onMoveEnd(MoveGestureDetector detector, float velocityX, float velocityY) {
      MapLibreMapController.this.onMoveEnd(detector);
    }
  }
}
